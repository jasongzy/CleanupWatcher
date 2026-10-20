package com.jasongzy.cleanupwatcher;

public final class ProcessFixture {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "stall" -> Thread.sleep(30_000);
            case "flood" -> System.out.write(new byte[2 * 1024 * 1024]);
            case "echo" -> System.in.transferTo(System.out);
            case "invalid" -> System.out.write(255);
            case "invalid-line" -> System.out.write(new byte[] {(byte) 255, '\n'});
            case "stream" -> {
                Class<?> handle = Class.forName("java.lang.ProcessHandle");
                String line = "测试:" + handle.getMethod("pid").invoke(handle.getMethod("current").invoke(null)) + "\n";
                for (byte value : line.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                    System.out.write(value);
                    System.out.flush();
                }
                System.in.read();
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
        System.out.flush();
    }
}
