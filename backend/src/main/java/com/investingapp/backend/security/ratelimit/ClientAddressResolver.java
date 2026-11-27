package com.investingapp.backend.security.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The caller's network address, for the per-address limits. The one place that decides it.
 *
 * It reads the first header in {@code app.rate-limit.address-headers} that has a value, and falls
 * back to the socket's own address. The default order is Cloudflare's header, then
 * X-Forwarded-For: a caller cannot set Cloudflare's header when the request really came through
 * Cloudflare.
 *
 * KNOWN LIMIT: a caller who reaches the backend without going through the proxy can put anything
 * in those headers and so pick a new "address" for every request. Per-address limits are therefore
 * best effort. They slow an honest network and a lazy attacker. The limits that cannot be dodged
 * are the ones keyed on the target: the account, the email or the signed-in user. Closing this
 * properly depends on how the hosting is set up (block direct access, or name the one header the
 * host itself sets), which is a deployment decision and is why the header list is a setting.
 */
@Component
public class ClientAddressResolver {

    private static final int MAX_LENGTH = 64;

    /** Only hex digits, colons and dots: nothing that could be a host name to look up. */
    private static final Pattern IPV6_CHARACTERS = Pattern.compile("[0-9a-fA-F:.]+");

    private final List<String> headers;

    public ClientAddressResolver(
            @Value("${app.rate-limit.address-headers:CF-Connecting-IP,X-Forwarded-For}") String headers) {
        this.headers = Arrays.stream(headers.split(",")).map(String::trim).filter(h -> !h.isEmpty()).toList();
    }

    public String resolve(HttpServletRequest request) {
        for (String header : headers) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank()) {
                String first = value.split(",")[0].trim();
                if (!first.isEmpty()) {
                    return network(first.length() > MAX_LENGTH ? first.substring(0, MAX_LENGTH) : first);
                }
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : network(remote);
    }

    /**
     * One home or phone connection owns a whole block of IPv6 addresses (a /64) and can use a new
     * one for every request. So an IPv6 caller is counted by that block, not by the single address.
     * An IPv4 address, and anything that is not an address at all, is returned unchanged.
     */
    static String network(String address) {
        if (address.indexOf(':') < 0 || !IPV6_CHARACTERS.matcher(address).matches()) {
            return address;
        }
        try {
            // A literal address: getByName parses it and never does a lookup.
            byte[] bytes = InetAddress.getByName(address).getAddress();
            if (bytes.length != 16) {
                return address;
            }
            return String.format("%02x%02x:%02x%02x:%02x%02x:%02x%02x::/64",
                    bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7]);
        } catch (Exception e) {
            return address;
        }
    }
}
