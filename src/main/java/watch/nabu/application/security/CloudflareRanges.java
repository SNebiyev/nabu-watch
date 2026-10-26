package watch.nabu.application.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Cloudflare's published edge address ranges, used to tell when a probed IP
 * belongs to Cloudflare's proxy and not to the site's own server.
 *
 * <p>Source: https://www.cloudflare.com/ips-v4 and https://www.cloudflare.com/ips-v6,
 * copied on 2026-10-26. Cloudflare changes these rarely; refresh the lists when it does.
 */
final class CloudflareRanges {

    static final String NAME = "Cloudflare";

    /**
     * Ports Cloudflare's edge accepts for every proxied site (standard proxied HTTP and HTTPS ports),
     * so an open connection there says nothing about the origin. Extend this set when more probed
     * ports are added to {@link OpenPortsInspector}.
     */
    static final List<Integer> EDGE_PORTS = List.of(80, 8080, 8880, 2052, 2082, 2086, 2095,
            443, 2053, 2083, 2087, 2096, 8443);

    private static final List<Cidr> RANGES = Cidr.parseAll(List.of(
            // IPv4
            "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
            "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
            "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
            "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
            // IPv6
            "2400:cb00::/32", "2606:4700::/32", "2803:f800::/32", "2405:b500::/32",
            "2405:8100::/32", "2a06:98c0::/29", "2c0f:f248::/32"));

    private CloudflareRanges() { }

    static boolean contains(InetAddress address) {
        byte[] bytes = address.getAddress();
        for (Cidr range : RANGES) {
            if (range.contains(bytes)) return true;
        }
        return false;
    }

    private record Cidr(byte[] network, int prefixLength) {

        static List<Cidr> parseAll(List<String> cidrs) {
            return cidrs.stream().map(Cidr::parse).toList();
        }

        static Cidr parse(String cidr) {
            int slash = cidr.indexOf('/');
            try {
                // Literal addresses only, no DNS lookup happens here.
                byte[] network = InetAddress.getByName(cidr.substring(0, slash)).getAddress();
                return new Cidr(network, Integer.parseInt(cidr.substring(slash + 1)));
            } catch (UnknownHostException e) {
                throw new IllegalStateException("Bad CIDR: " + cidr, e);
            }
        }

        boolean contains(byte[] address) {
            if (address.length != network.length) return false;
            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) return false;
            }
            int rest = prefixLength % 8;
            if (rest == 0) return true;
            int mask = 0xFF << (8 - rest);
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
