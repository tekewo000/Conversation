package jp.tekewo.conversation;

final class NativeRag {
    static { System.loadLibrary("conversation"); }
    static native long openTokenizer(byte[] model);
    static native long[] tokenize(long tokenizer, byte[] text);
    static native void closeTokenizer(long tokenizer);
    static native long openIndex();
    static native void put(long index, long id, float[] vector);
    static native void remove(long index, long id);
    static native long[] search(long index, float[] vector);
    static native void closeIndex(long index);
}
