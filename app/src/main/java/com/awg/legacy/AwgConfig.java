package com.awg.legacy;

import android.util.Base64;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parser and UAPI serializer for WireGuard and AmneziaWG (1.5 / 2.0 / 3.0 / 3.1) configurations.
 * Supports:
 * - Basic AWG 1.5 obfuscation: Jc, Jmin, Jmax, S1, S2, H1, H2, H3, H4
 * - Extended AWG 2.0 / 3.0 / 3.1: S3, S4, range headers H1-H4 (e.g. "100000-200000"),
 *   and CPS protocol mimicry signature packets I1, I2, I3, I4, I5
 * - Passthrough of any unknown keys in [Interface] or [Peer] directly into UAPI (key=value\n).
 */
public final class AwgConfig {

    public static final class CidrAddress {
        public final String address;
        public final int prefixLength;
        public final boolean isIpv6;

        public CidrAddress(String address, int prefixLength, boolean isIpv6) {
            this.address = address;
            this.prefixLength = prefixLength;
            this.isIpv6 = isIpv6;
        }
    }

    public static final class PeerSection {
        public String publicKeyHex;
        public String presharedKeyHex;
        public String endpoint;
        public int persistentKeepalive = 0;
        public final List<CidrAddress> allowedIps = new ArrayList<CidrAddress>();
        public final Map<String, String> passthroughKeys = new LinkedHashMap<String, String>();
    }

    public String privateKeyHex;
    public int listenPort = 0;
    public int mtu = 1280;
    public final List<CidrAddress> addresses = new ArrayList<CidrAddress>();
    public final List<String> dnsServers = new ArrayList<String>();

    // AmneziaWG 1.5 / 2.0 / 3.0 / 3.1 Obfuscation & CPS parameters
    public final Map<String, String> awgObfuscation = new LinkedHashMap<String, String>();
    // Unknown [Interface] parameters passed straight to UAPI
    public final Map<String, String> interfacePassthrough = new LinkedHashMap<String, String>();
    public final List<PeerSection> peers = new ArrayList<PeerSection>();

    private static final String[] KNOWN_AWG_KEYS = new String[]{
            "jc", "jmin", "jmax",
            "s1", "s2", "s3", "s4",
            "h1", "h2", "h3", "h4",
            "i1", "i2", "i3", "i4", "i5"
    };

    private static boolean isKnownAwgKey(String lowerKey) {
        for (int i = 0; i < KNOWN_AWG_KEYS.length; i++) {
            if (KNOWN_AWG_KEYS[i].equals(lowerKey)) {
                return true;
            }
        }
        return false;
    }

    public static AwgConfig parse(String rawConf) throws IllegalArgumentException {
        if (rawConf == null || rawConf.trim().isEmpty()) {
            throw new IllegalArgumentException("Configuration text is empty.");
        }

        AwgConfig config = new AwgConfig();
        String currentSection = "";
        PeerSection currentPeer = null;

        BufferedReader reader = new BufferedReader(new StringReader(rawConf));
        String line;
        int lineNumber = 0;

        try {
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = stripInlineComment(line).trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    String sectionName = trimmed.substring(1, trimmed.length() - 1).trim().toLowerCase(Locale.US);
                    if ("interface".equals(sectionName)) {
                        currentSection = "interface";
                        currentPeer = null;
                    } else if ("peer".equals(sectionName)) {
                        currentSection = "peer";
                        currentPeer = new PeerSection();
                        config.peers.add(currentPeer);
                    } else {
                        currentSection = sectionName;
                        currentPeer = null;
                    }
                    continue;
                }

                int eqIndex = trimmed.indexOf('=');
                if (eqIndex <= 0) {
                    throw new IllegalArgumentException("Malformed line " + lineNumber + ": " + trimmed);
                }

                String rawKey = trimmed.substring(0, eqIndex).trim();
                String lowerKey = rawKey.toLowerCase(Locale.US);
                String value = trimmed.substring(eqIndex + 1).trim();

                if ("interface".equals(currentSection)) {
                    parseInterfaceEntry(config, lowerKey, value, lineNumber);
                } else if ("peer".equals(currentSection) && currentPeer != null) {
                    parsePeerEntry(currentPeer, lowerKey, value, lineNumber);
                } else {
                    throw new IllegalArgumentException("Key '" + rawKey + "' outside [Interface] or [Peer] at line " + lineNumber);
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed reading config: " + e.getMessage(), e);
        }

        if (config.privateKeyHex == null || config.privateKeyHex.isEmpty()) {
            throw new IllegalArgumentException("Missing PrivateKey in [Interface] section.");
        }
        if (config.addresses.isEmpty()) {
            throw new IllegalArgumentException("Missing Address in [Interface] section.");
        }
        if (config.peers.isEmpty()) {
            throw new IllegalArgumentException("At least one [Peer] section is required.");
        }
        for (int i = 0; i < config.peers.size(); i++) {
            PeerSection p = config.peers.get(i);
            if (p.publicKeyHex == null || p.publicKeyHex.isEmpty()) {
                throw new IllegalArgumentException("Peer #" + (i + 1) + " is missing PublicKey.");
            }
        }

        return config;
    }

    private static void parseInterfaceEntry(AwgConfig config, String key, String value, int lineNumber) {
        if ("privatekey".equals(key)) {
            config.privateKeyHex = decodeKeyToHex(value, "PrivateKey", lineNumber);
        } else if ("address".equals(key)) {
            String[] parts = value.split(",");
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i].trim();
                if (!part.isEmpty()) {
                    config.addresses.add(parseCidr(part, 32, 128, lineNumber));
                }
            }
        } else if ("dns".equals(key)) {
            String[] parts = value.split(",");
            for (int i = 0; i < parts.length; i++) {
                String dns = parts[i].trim();
                if (!dns.isEmpty()) {
                    config.dnsServers.add(dns);
                }
            }
        } else if ("mtu".equals(key)) {
            try {
                config.mtu = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid MTU at line " + lineNumber + ": " + value);
            }
        } else if ("listenport".equals(key)) {
            try {
                config.listenPort = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid ListenPort at line " + lineNumber + ": " + value);
            }
        } else if (isKnownAwgKey(key)) {
            validateAwgParameter(key, value, lineNumber);
            config.awgObfuscation.put(key, value);
        } else {
            // Forward any unknown [Interface] key directly to UAPI as-is
            config.interfacePassthrough.put(key, value);
        }
    }

    private static void parsePeerEntry(PeerSection peer, String key, String value, int lineNumber) {
        if ("publickey".equals(key)) {
            peer.publicKeyHex = decodeKeyToHex(value, "PublicKey", lineNumber);
        } else if ("presharedkey".equals(key)) {
            peer.presharedKeyHex = decodeKeyToHex(value, "PresharedKey", lineNumber);
        } else if ("endpoint".equals(key)) {
            peer.endpoint = value;
        } else if ("persistentkeepalive".equals(key)) {
            if (!"off".equalsIgnoreCase(value)) {
                try {
                    peer.persistentKeepalive = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid PersistentKeepalive at line " + lineNumber + ": " + value);
                }
            }
        } else if ("allowedips".equals(key)) {
            String[] parts = value.split(",");
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i].trim();
                if (!part.isEmpty()) {
                    peer.allowedIps.add(parseCidr(part, 32, 128, lineNumber));
                }
            }
        } else {
            // Forward any unknown [Peer] key directly to UAPI as-is
            peer.passthroughKeys.put(key, value);
        }
    }

    private static void validateAwgParameter(String key, String value, int lineNumber) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Empty AmneziaWG parameter '" + key + "' at line " + lineNumber);
        }
        // Jc, Jmin, Jmax, S1, S2, S3, S4 are integers
        if ("jc".equals(key) || "jmin".equals(key) || "jmax".equals(key)
                || "s1".equals(key) || "s2".equals(key) || "s3".equals(key) || "s4".equals(key)) {
            try {
                Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("AmneziaWG parameter '" + key + "' must be an integer at line " + lineNumber + ": " + value);
            }
        }
        // H1..H4 can be single uint32 ("123456") or range ("100000-200000") in AWG 2.0/3.0/3.1
        if ("h1".equals(key) || "h2".equals(key) || "h3".equals(key) || "h4".equals(key)) {
            int dashIdx = value.indexOf('-');
            if (dashIdx > 0) {
                String startStr = value.substring(0, dashIdx).trim();
                String endStr = value.substring(dashIdx + 1).trim();
                try {
                    long startVal = Long.parseLong(startStr);
                    long endVal = Long.parseLong(endStr);
                    if (startVal < 0 || endVal < startVal) {
                        throw new IllegalArgumentException("Invalid H-range '" + value + "' at line " + lineNumber);
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid H-range '" + value + "' at line " + lineNumber);
                }
            }
        }
    }

    /**
     * Converts the parsed configuration into a UAPI string suitable for wgTurnOn().
     * Preserves all AmneziaWG 1.5 / 2.0 / 3.0 / 3.1 parameters and unknown passthrough keys.
     */
    public String toUapiString() {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("private_key=").append(privateKeyHex).append('\n');
        if (listenPort > 0) {
            sb.append("listen_port=").append(listenPort).append('\n');
        }

        // AmneziaWG 1.5 / 2.0 / 3.0 / 3.1 obfuscation & CPS parameters
        for (Map.Entry<String, String> entry : awgObfuscation.entrySet()) {
            sb.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }

        // Unknown [Interface] passthrough keys
        for (Map.Entry<String, String> entry : interfacePassthrough.entrySet()) {
            sb.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }

        sb.append("replace_peers=true\n");

        for (int i = 0; i < peers.size(); i++) {
            PeerSection peer = peers.get(i);
            sb.append("public_key=").append(peer.publicKeyHex).append('\n');
            if (peer.presharedKeyHex != null && !peer.presharedKeyHex.isEmpty()) {
                sb.append("preshared_key=").append(peer.presharedKeyHex).append('\n');
            }
            if (peer.endpoint != null && !peer.endpoint.isEmpty()) {
                sb.append("endpoint=").append(peer.endpoint).append('\n');
            }
            if (peer.persistentKeepalive > 0) {
                sb.append("persistent_keepalive_interval=").append(peer.persistentKeepalive).append('\n');
            }
            sb.append("replace_allowed_ips=true\n");
            for (int j = 0; j < peer.allowedIps.size(); j++) {
                CidrAddress cidr = peer.allowedIps.get(j);
                sb.append("allowed_ip=").append(cidr.address).append('/').append(cidr.prefixLength).append('\n');
            }
            // Unknown [Peer] passthrough keys
            for (Map.Entry<String, String> entry : peer.passthroughKeys.entrySet()) {
                sb.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
            }
        }

        return sb.toString();
    }

    public String detectProtocolRevision() {
        boolean hasCps = awgObfuscation.containsKey("i1") || awgObfuscation.containsKey("i2")
                || awgObfuscation.containsKey("i3") || awgObfuscation.containsKey("i4")
                || awgObfuscation.containsKey("i5");
        boolean hasS3S4 = awgObfuscation.containsKey("s3") || awgObfuscation.containsKey("s4");
        boolean hasRangeHeaders = false;
        for (int i = 1; i <= 4; i++) {
            String h = awgObfuscation.get("h" + i);
            if (h != null && h.contains("-")) {
                hasRangeHeaders = true;
                break;
            }
        }
        if (hasCps && (hasS3S4 || hasRangeHeaders)) {
            return "AmneziaWG 3.1 (CPS + S3/S4 + Range H1-H4)";
        }
        if (hasCps) {
            return "AmneziaWG 3.0 (CPS Signature Packets)";
        }
        if (hasS3S4 || hasRangeHeaders) {
            return "AmneziaWG 2.0 (S3/S4 / Range Headers)";
        }
        if (!awgObfuscation.isEmpty()) {
            return "AmneziaWG 1.5 (Jc/Jmin/Jmax/S1/S2/H1-H4)";
        }
        return "Standard WireGuard";
    }

    private static CidrAddress parseCidr(String raw, int defaultV4Prefix, int defaultV6Prefix, int lineNumber) {
        String ipPart = raw;
        boolean isIpv6 = raw.contains(":");
        int prefix = isIpv6 ? defaultV6Prefix : defaultV4Prefix;

        int slashIdx = raw.indexOf('/');
        if (slashIdx > 0) {
            ipPart = raw.substring(0, slashIdx).trim();
            String prefixStr = raw.substring(slashIdx + 1).trim();
            try {
                prefix = Integer.parseInt(prefixStr);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid CIDR prefix at line " + lineNumber + ": " + raw);
            }
        }
        return new CidrAddress(ipPart, prefix, isIpv6);
    }

    private static String decodeKeyToHex(String input, String fieldName, int lineNumber) {
        String cleaned = input.trim();
        // Accept already hex-encoded 64-char keys
        if (cleaned.length() == 64 && cleaned.matches("^[0-9a-fA-F]+$")) {
            return cleaned.toLowerCase(Locale.US);
        }
        try {
            byte[] decoded = Base64.decode(cleaned, Base64.DEFAULT);
            if (decoded == null || decoded.length != 32) {
                throw new IllegalArgumentException(fieldName + " at line " + lineNumber + " must decode to 32 bytes (got " + (decoded == null ? 0 : decoded.length) + ").");
            }
            char[] hexChars = new char[64];
            final char[] hexAlphabet = "0123456789abcdef".toCharArray();
            for (int i = 0; i < 32; i++) {
                int v = decoded[i] & 0xFF;
                hexChars[i * 2] = hexAlphabet[v >>> 4];
                hexChars[i * 2 + 1] = hexAlphabet[v & 0x0F];
            }
            return new String(hexChars);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Base64/Hex in " + fieldName + " at line " + lineNumber + ": " + e.getMessage());
        }
    }

    private static String stripInlineComment(String line) {
        // Do not strip '#' inside CPS tags like <b 0x...> or quoted values unless preceded by whitespace
        int hashIdx = line.indexOf('#');
        if (hashIdx == 0) {
            return "";
        }
        if (hashIdx > 0 && Character.isWhitespace(line.charAt(hashIdx - 1))) {
            return line.substring(0, hashIdx);
        }
        return line;
    }
}
