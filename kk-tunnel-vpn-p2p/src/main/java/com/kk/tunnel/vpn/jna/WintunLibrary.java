package com.kk.tunnel.vpn.jna;

import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;

public interface WintunLibrary extends Library {
    // 下载地址为https://www.wintun.net/builds/wintun-0.14.1.zip，解压并将amd64目录下的wintun.dll文件存放到resource路径下
    WintunLibrary INSTANCE = Native.load("wintun", WintunLibrary.class,
            java.util.Map.of(Library.OPTION_FUNCTION_MAPPER, WintunFunctionMapper.INSTANCE));

    /**
     * 创建适配器
     * @param Name 适配器名称 (如 "KK-VPN")
     * @param TunnelType 隧道类型 (通常 "Tunnel")
     * @param RequestedGuid 可为 null，让系统自动生成
     * @return 适配器句柄
     */
    Pointer WintunCreateAdapter(WString Name, WString TunnelType, Pointer RequestedGuid);

    /**
     * 打开现有适配器
     */
    Pointer WintunOpenAdapter(WString Name);

    /**
     * 关闭适配器
     */
    void WintunCloseAdapter(Pointer Adapter);

    /**
     * 开启会话 (启动数据流)
     * @param Adapter 适配器句柄
     * @param Capacity 环形缓冲区大小 (推荐 0x400000 = 4MB)
     * @return 会话句柄
     */
    Pointer WintunStartSession(Pointer Adapter, int Capacity);

    /**
     * 结束会话
     */
    void WintunEndSession(Pointer Session);

    /**
     * 读取数据包 (非阻塞或阻塞，取决于实现，通常这是从内核环形缓冲区取指针)
     * @param Session 会话句柄
     * @param PacketSize 输出参数，返回包大小
     * @return 指向数据包内容的指针，如果队列为空返回 NULL
     */
    Pointer WintunReceivePacket(Pointer Session, IntByReference PacketSize);

    /**
     * 释放读取的数据包 (读取处理完后必须调用！)
     */
    void WintunReleaseReceivePacket(Pointer Session, Pointer Packet);

    /**
     * 分配发送缓冲区
     * @param Session 会话句柄
     * @param PacketSize 要发送的数据大小
     * @return 指向写入缓冲区的指针
     */
    Pointer WintunAllocateSendPacket(Pointer Session, int PacketSize);

    /**
     * 发送数据包 (数据写入缓冲区后调用)
     */
    void WintunSendPacket(Pointer Session, Pointer Packet);

    // 简单的函数名映射器，防止修饰名问题
    class WintunFunctionMapper implements com.sun.jna.FunctionMapper {
        public static final WintunFunctionMapper INSTANCE = new WintunFunctionMapper();
        @Override
        public String getFunctionName(NativeLibrary library, java.lang.reflect.Method method) {
            return method.getName();
        }
    }
}