package dev.junxiex.mikuvideo.protocol;

/** 协议编解码失败。属于数据错误，不应导致服务端或客户端崩溃。 */
public class ProtocolException extends RuntimeException {
    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
