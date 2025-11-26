package csc4010.chat;

public enum MessageType {
    HELLO,
    WELCOME,
    PEERS,
    CHAT,
    CHAT_ACK,
    HISTORY_REQ,
    HISTORY_DONE,
    HEARTBEAT,
    LEAVE,
    RESEND_REQ,
    RESEND_RES,
    FILE_META,
    FILE_CHUNK,
    DISCOVER,
    DISCOVER_RES
}
