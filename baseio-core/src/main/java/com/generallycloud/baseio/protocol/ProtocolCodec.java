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
package com.generallycloud.baseio.protocol;

import java.io.IOException;

import com.generallycloud.baseio.buffer.ByteBuf;
import com.generallycloud.baseio.component.ChannelContext;
import com.generallycloud.baseio.component.NioSocketChannel;

/**
 * @author wangkai
 *
 */
public interface ProtocolCodec {

    Future createPINGPacket(NioSocketChannel channel);

    Future createPONGPacket(NioSocketChannel channel, Future ping);

    // 可能会遭受一种攻击，比如最大可接收数据为100，客户端传输到99后暂停，
    // 这样多次以后可能会导致内存溢出
    Future decode(NioSocketChannel channel, ByteBuf src) throws IOException;

    // 注意：encode失败要release掉encode过程中申请的内存
    void encode(NioSocketChannel channel, Future future) throws IOException;

    String getProtocolId();

    void initialize(ChannelContext context);

}
