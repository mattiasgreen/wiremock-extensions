package com.github.mattiasgreen.wiremock.netem.matcher;

import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;

/**
 * Socket-level client IP and CIDR subnet matcher for TCP connections.
 */
public final class SourceIpMatcher {

    private SourceIpMatcher() {}

    /**
     * Checks if the given client socket matches the configured client IP or CIDR block.
     *
     * @param clientSocket the incoming client socket
     * @param configuredIp the target IP or CIDR (e.g., "127.0.0.1", "10.0.0.0/8"). If null/blank, matches all.
     * @return true if the socket matches or if configuredIp is blank
     */
    public static boolean matches(Socket clientSocket, String configuredIp) {
        if (configuredIp == null || configuredIp.isBlank()) {
            return true;
        }
        if (clientSocket == null || clientSocket.getInetAddress() == null) {
            return false;
        }
        return matches(clientSocket.getInetAddress(), configuredIp.trim());
    }

    /**
     * Checks if the given InetAddress matches the configured client IP or CIDR block.
     *
     * @param address the client's InetAddress
     * @param configured the target IP or CIDR
     * @return true if matches
     */
    public static boolean matches(InetAddress address, String configured) {
        if (configured == null || configured.isBlank()) {
            return true;
        }
        if (address == null) {
            return false;
        }

        String target = configured.trim();
        if (target.contains("/")) {
            return matchesCidr(address, target);
        }

        if (address.getHostAddress().equalsIgnoreCase(target)) {
            return true;
        }

        try {
            InetAddress targetAddr = InetAddress.getByName(target);
            return address.equals(targetAddr);
        } catch (UnknownHostException ignored) {
            return false;
        }
    }

    private static boolean matchesCidr(InetAddress address, String cidr) {
        String[] parts = cidr.split("/");
        if (parts.length != 2) {
            return false;
        }
        try {
            InetAddress subnetAddr = InetAddress.getByName(parts[0].trim());
            int prefixLen = Integer.parseInt(parts[1].trim());

            byte[] addrBytes = address.getAddress();
            byte[] subnetBytes = subnetAddr.getAddress();

            if (addrBytes.length != subnetBytes.length) {
                return false;
            }

            int maxBits = addrBytes.length * 8;
            if (prefixLen < 0 || prefixLen > maxBits) {
                return false;
            }

            int bytesToCheck = prefixLen / 8;
            int bitsToCheck = prefixLen % 8;

            for (int i = 0; i < bytesToCheck; i++) {
                if (addrBytes[i] != subnetBytes[i]) {
                    return false;
                }
            }

            if (bitsToCheck > 0) {
                int mask = (0xFF << (8 - bitsToCheck)) & 0xFF;
                if ((addrBytes[bytesToCheck] & mask) != (subnetBytes[bytesToCheck] & mask)) {
                    return false;
                }
            }

            return true;
        } catch (UnknownHostException | NumberFormatException ignored) {
            return false;
        }
    }
}
