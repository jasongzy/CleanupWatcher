package com.jasongzy.cleanupwatcher;

public final class ProcessFixture {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "stall" -> Thread.sleep(30_000);
            case "flood" -> System.out.write(new byte[2 * 1024 * 1024]);
            case "echo" -> System.in.transferTo(System.out);
            case "invalid" -> System.out.write(255);
            default -> throw new IllegalArgumentException(args[0]);
        }
        System.out.flush();
    }
}
