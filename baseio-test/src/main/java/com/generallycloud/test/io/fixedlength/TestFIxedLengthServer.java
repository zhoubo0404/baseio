/*
 * Copyright 2015-2017 GenerallyCloud.com
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.generallycloud.test.io.fixedlength;

import java.io.File;

import com.generallycloud.baseio.codec.fixedlength.FixedLengthCodec;
import com.generallycloud.baseio.codec.fixedlength.FixedLengthFuture;
import com.generallycloud.baseio.common.FileUtil;
import com.generallycloud.baseio.component.ChannelAcceptor;
import com.generallycloud.baseio.component.ChannelContext;
import com.generallycloud.baseio.component.IoEventHandle;
import com.generallycloud.baseio.component.LoggerChannelOpenListener;
import com.generallycloud.baseio.component.NioSocketChannel;
import com.generallycloud.baseio.component.ssl.SSLUtil;
import com.generallycloud.baseio.component.ssl.SslContext;
import com.generallycloud.baseio.configuration.Configuration;
import com.generallycloud.baseio.protocol.Future;

public class TestFIxedLengthServer {

    public static void main(String[] args) throws Exception {

        IoEventHandle eventHandleAdaptor = new IoEventHandle() {

            @Override
            public void accept(NioSocketChannel channel, Future future) throws Exception {
                FixedLengthFuture f = (FixedLengthFuture) future;
                future.write("yes server already accept your message:", channel);
                future.write(f.getReadText(), channel);
                channel.flush(future);
            }
        };
        ChannelContext context = new ChannelContext(new Configuration(8300));
        ChannelAcceptor acceptor = new ChannelAcceptor(context);
        context.addChannelEventListener(new LoggerChannelOpenListener());
        //		context.addChannelEventListener(new SocketChannelAliveSEListener());
        context.setIoEventHandle(eventHandleAdaptor);
        context.setProtocolCodec(new FixedLengthCodec());
        File certificate = FileUtil.readFileByCls("generallycloud.com.crt");
        File privateKey = FileUtil.readFileByCls("generallycloud.com.key");
        SslContext sslContext = SSLUtil.initServer(privateKey, certificate);
        context.setSslContext(sslContext);
        acceptor.bind();
    }

}
