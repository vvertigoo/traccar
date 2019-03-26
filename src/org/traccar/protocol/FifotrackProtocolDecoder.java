/*
 * Copyright 2016 - 2019 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import org.traccar.BaseProtocolDecoder;
import org.traccar.DeviceSession;
import org.traccar.NetworkMessage;
import org.traccar.Protocol;
import org.traccar.helper.Checksum;
import org.traccar.helper.DataConverter;
import org.traccar.helper.Parser;
import org.traccar.helper.PatternBuilder;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.CellTower;
import org.traccar.model.Network;
import org.traccar.model.Position;

import java.net.SocketAddress;
import java.util.regex.Pattern;

public class FifotrackProtocolDecoder extends BaseProtocolDecoder {

    private ByteBuf photo;

    public FifotrackProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    private static final Pattern PATTERN = new PatternBuilder()
            .text("$$")
            .number("d+,")                       // length
            .number("(d+),")                     // imei
            .number("x+,")                       // index
            .expression("[^,]+,")                // type
            .number("(d+)?,")                    // alarm
            .number("(dd)(dd)(dd)")              // date (yymmdd)
            .number("(dd)(dd)(dd),")             // time (hhmmss)
            .number("([AV]),")                   // validity
            .number("(-?d+.d+),")                // latitude
            .number("(-?d+.d+),")                // longitude
            .number("(d+),")                     // speed
            .number("(d+),")                     // course
            .number("(-?d+),")                   // altitude
            .number("(d+),")                     // odometer
            .number("d+,")                       // runtime
            .number("(xxxx),")                   // status
            .number("(x+)?,")                    // input
            .number("(x+)?,")                    // output
            .number("(d+)|")                     // mcc
            .number("(d+)|")                     // mnc
            .number("(x+)|")                     // lac
            .number("(x+),")                     // cid
            .number("([x|]+)")                   // adc
            .expression(",([^,]+)")              // rfid
            .expression(",([^*]+)").optional(2)  // sensors
            .any()
            .compile();

    private static final Pattern PATTERN_PHOTO = new PatternBuilder()
            .text("$$")
            .number("d+,")                       // length
            .number("(d+),")                     // imei
            .any()
            .number("(d+),")                     // length
            .expression("([^*]+)")               // photo id
            .text("*")
            .number("xx")
            .compile();

    private static final Pattern PATTERN_PHOTO_DATA = new PatternBuilder()
            .text("$$")
            .number("d+,")                       // length
            .number("(d+),")                     // imei
            .expression("([^*]+)")               // photo id
            .number("(d+),")                     // offset
            .number("(d+),")                     // size
            .number("(x+)")                      // data
            .text("*")
            .number("xx")
            .compile();

    private void requestPhoto(Channel channel, SocketAddress socketAddress, String imei, String file) {
        if (channel != null) {
            String content = "D06," + file + "," + photo.writerIndex() + "," + Math.min(1024, photo.writableBytes());
            int length = 1 + imei.length() + 1 + content.length() + 5;
            String response = String.format("@@%02d,%s,%s*", length, imei, content);
            response += Checksum.sum(response) + "\r\n";
            channel.writeAndFlush(new NetworkMessage(response, socketAddress));
        }
    }

    private Object decodeLocation(
            Channel channel, SocketAddress remoteAddress, String sentence) {

        Parser parser = new Parser(PATTERN, sentence);
        if (!parser.matches()) {
            return null;
        }

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, parser.next());
        if (deviceSession == null) {
            return null;
        }

        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());

        position.set(Position.KEY_ALARM, parser.next());

        position.setTime(parser.nextDateTime());

        position.setValid(parser.next().equals("A"));
        position.setLatitude(parser.nextDouble(0));
        position.setLongitude(parser.nextDouble(0));
        position.setSpeed(UnitsConverter.knotsFromKph(parser.nextInt(0)));
        position.setCourse(parser.nextInt(0));
        position.setAltitude(parser.nextInt(0));

        position.set(Position.KEY_ODOMETER, parser.nextLong(0));
        position.set(Position.KEY_STATUS, parser.nextHexInt(0));
        if (parser.hasNext()) {
            position.set(Position.KEY_INPUT, parser.nextHexInt(0));
        }
        if (parser.hasNext()) {
            position.set(Position.KEY_OUTPUT, parser.nextHexInt(0));
        }

        position.setNetwork(new Network(CellTower.from(
                parser.nextInt(0), parser.nextInt(0), parser.nextHexInt(0), parser.nextHexInt(0))));

        String[] adc = parser.next().split("\\|");
        for (int i = 0; i < adc.length; i++) {
            position.set(Position.PREFIX_ADC + (i + 1), Integer.parseInt(adc[i], 16));
        }

        position.set(Position.KEY_DRIVER_UNIQUE_ID, parser.next());

        if (parser.hasNext()) {
            String[] sensors = parser.next().split("\\|");
            for (int i = 0; i < sensors.length; i++) {
                position.set(Position.PREFIX_IO + (i + 1), sensors[i]);
            }
        }

        return position;
    }

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        String sentence = (String) msg;
        int typeIndex = sentence.indexOf(',', sentence.indexOf(',', sentence.indexOf(',') + 1) + 1) + 1;
        String type = sentence.substring(typeIndex, typeIndex + 3);

        if (type.equals("D05")) {
            Parser parser = new Parser(PATTERN_PHOTO, sentence);
            if (parser.matches()) {
                String imei = parser.next();
                int length = parser.nextInt();
                String photoId = parser.next();
                photo = Unpooled.buffer(length);
                requestPhoto(channel, remoteAddress, imei, photoId);
            }
        } else if (type.equals("D06")) {
            Parser parser = new Parser(PATTERN_PHOTO_DATA, sentence);
            if (parser.matches()) {
                String imei = parser.next();
                String photoId = parser.next();
                parser.nextInt(); // offset
                parser.nextInt(); // size
                photo.writeBytes(DataConverter.parseHex(parser.next()));
                requestPhoto(channel, remoteAddress, imei, photoId);
            }
        } else {
            return decodeLocation(channel, remoteAddress, sentence);
        }

        return null;
    }

}
