package com.kyonggi.backend.auth.support;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kyonggi.backend.infra.AbstractIntegrationTest;

public final class MailhogSupport {
    private MailhogSupport() {}

    private static final ObjectMapper om = new ObjectMapper();
    private static final HttpClient http = HttpClient.newHttpClient();

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(3);
    private static final long POLL_INTERVAL_MS = 200;

    private static final Pattern OTP_6 = Pattern.compile("\\b(\\d{6})\\b");

    // =?UTF-8?B?...?= / =?UTF-8?Q?...?=
    private static final Pattern MIME_WORD =
            Pattern.compile("=\\?([^?]+)\\?([bBqQ])\\?([^?]+)\\?=");

    // base64-ish (MailHog가 body를 base64로 주는 케이스가 있음)
    private static final Pattern BASE64ISH =
            Pattern.compile("^[A-Za-z0-9+/\\r\\n=]+$");

    /** ✅ Testcontainers 매핑 포트 기반 MailHog base URL */
    private static String baseUrl() {
        return "http://" + AbstractIntegrationTest.getMailhogHost() + ":" + AbstractIntegrationTest.getMappedMailhogHttpPort();
    }

    /** MailHog 전체 메일 삭제 (Flow 테스트 전 @BeforeEach에서 호출) */
    public static void clearAll() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/v1/messages"))
                .DELETE()
                .timeout(HTTP_TIMEOUT)
                .build();

        http.send(req, HttpResponse.BodyHandlers.discarding());
    }

    /** 특정 수신자에게 온 메일에서 6자리 OTP를 기다렸다가 반환 */
    public static String awaitOtpFor(String toEmail, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        JsonNode lastRoot = null;

        while (System.nanoTime() < deadline) {
            HttpResponse<String> res = fetchV2Messages();
            if (res.statusCode() / 100 != 2) {
                Thread.sleep(POLL_INTERVAL_MS);
                continue;
            }

            JsonNode root = om.readTree(res.body());
            lastRoot = root;

            String otp = tryFindOtpFromRoot(root, toEmail);
            if (otp != null) return otp;

            Thread.sleep(POLL_INTERVAL_MS);
        }

        throw new AssertionError(
                "OTP email not found in MailHog for: " + toEmail +
                " (baseUrl=" + baseUrl() + ")\n" +
                "debug=" + summarize(lastRoot)
        );
    }

    private static HttpResponse<String> fetchV2Messages() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/v2/messages?limit=50"))
                .GET()
                .timeout(HTTP_TIMEOUT)
                .build();

        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String tryFindOtpFromRoot(JsonNode root, String toEmail) {
        JsonNode items = root.get("items");
        if (items == null || !items.isArray()) return null;

        for (JsonNode item : items) {
            if (!recipientMatches(item, toEmail)) continue;

            String subjectRaw = headerFirst(item, "Subject");
            String subject = decodeAll(subjectRaw);

            // MailHog v2: Content.Body / MIME.Parts[].Body 가 base64(또는 QP)인 케이스가 있음
            String contentBody = decodeAll(safeText(item.at("/Content/Body")));
            String mimeBodies = decodeAll(collectMimeBodies(item));

            String haystack = (subject == null ? "" : subject) + "\n"
                    + (contentBody == null ? "" : contentBody) + "\n"
                    + (mimeBodies == null ? "" : mimeBodies);

            String otp = extractOtp(haystack);
            if (otp != null) return otp;
        }
        return null;
    }

    /** 수신자 매칭: (1) top-level To 배열 우선, (2) 헤더 To/Cc fallback, (3) 정보 없으면 통과 */
    private static boolean recipientMatches(JsonNode item, String email) {
        if (email == null || email.isBlank()) return true;

        String target = email.trim().toLowerCase(Locale.ROOT);

        // (1) top-level "To" (envelope recipients)
        JsonNode toArr = item.get("To");
        if (toArr != null && toArr.isArray() && toArr.size() > 0) {
            for (JsonNode r : toArr) {
                String addr = mailboxDomain(r);
                if (addr != null && addr.toLowerCase(Locale.ROOT).contains(target)) return true;
            }
            return false; // To가 있는데 매칭 실패면 다른 수신자 메일
        }

        // (2) fallback: Content.Headers.To / Cc
        JsonNode headers = item.at("/Content/Headers");
        if (headers != null && !headers.isMissingNode()) {
            if (containsInHeader(headers.get("To"), target)) return true;
            if (containsInHeader(headers.get("Cc"), target)) return true;

            String toText = flattenHeader(headers.get("To"));
            if (toText != null && toText.toLowerCase(Locale.ROOT).contains("undisclosed")) {
                return true;
            }
        }

        // (3) 애매하면 통과
        return true;
    }

    private static String mailboxDomain(JsonNode recipientNode) {
        if (recipientNode == null || recipientNode.isMissingNode()) return null;
        String mailbox = safeText(recipientNode.get("Mailbox"));
        String domain = safeText(recipientNode.get("Domain"));
        if (mailbox == null || mailbox.isBlank()) return null;
        if (domain == null || domain.isBlank()) return mailbox;
        return mailbox + "@" + domain;
    }

    private static boolean containsInHeader(JsonNode headerNode, String targetLower) {
        if (headerNode == null || headerNode.isMissingNode()) return false;

        if (headerNode.isArray()) {
            for (JsonNode n : headerNode) {
                String s = safeText(n);
                if (s != null && s.toLowerCase(Locale.ROOT).contains(targetLower)) return true;
            }
            return false;
        }

        String s = safeText(headerNode);
        return s != null && s.toLowerCase(Locale.ROOT).contains(targetLower);
    }

    private static String flattenHeader(JsonNode headerNode) {
        if (headerNode == null || headerNode.isMissingNode()) return null;
        if (!headerNode.isArray()) return safeText(headerNode);

        StringBuilder sb = new StringBuilder();
        for (JsonNode n : headerNode) {
            String s = safeText(n);
            if (s != null && !s.isBlank()) sb.append(s).append(" ");
        }
        return sb.toString().trim();
    }

    /** Content.Headers에서 headerName 첫 값 */
    private static String headerFirst(JsonNode item, String headerName) {
        JsonNode node = item.at("/Content/Headers/" + headerName);
        if (node == null || node.isMissingNode()) return null;

        if (node.isArray()) {
            return node.size() > 0 ? safeText(node.get(0)) : null;
        }
        return safeText(node);
    }

    /** multipart 대비: /MIME/Parts[].Body 전부 긁어서 합치기 (2-depth까지 방어) */
    private static String collectMimeBodies(JsonNode item) {
        JsonNode parts = item.at("/MIME/Parts");
        if (parts == null || parts.isMissingNode() || !parts.isArray() || parts.size() == 0) return "";

        List<String> bodies = new ArrayList<>();
        for (JsonNode p : parts) {
            String b = safeText(p.get("Body"));
            if (b != null && !b.isBlank()) bodies.add(b);

            JsonNode innerParts = p.at("/MIME/Parts");
            if (innerParts != null && innerParts.isArray()) {
                for (JsonNode ip : innerParts) {
                    String ib = safeText(ip.get("Body"));
                    if (ib != null && !ib.isBlank()) bodies.add(ib);
                }
            }
        }
        return String.join("\n", bodies);
    }

    private static String extractOtp(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher m = OTP_6.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String safeText(JsonNode node) {
        return (node == null || node.isMissingNode() || node.isNull()) ? null : node.asText("");
    }

    /** ===== 디코딩 파이프라인: MIME-word -> base64 -> quoted-printable(문자열) ===== */
    private static String decodeAll(String s) {
        if (s == null || s.isBlank()) return s;

        String out = s;

        // 1) MIME encoded-word (Subject에서 자주 나옴)
        out = decodeMimeEncodedWords(out);

        // 2) body가 base64로 통째로 오는 케이스 디코드 (너 로그에 bodyPreview가 딱 이 케이스였음)
        out = maybeDecodeBase64ToText(out);

        // 3) quoted-printable 흔한 형태 디코드
        out = decodeQuotedPrintableText(out);

        return out;
    }

    private static String decodeMimeEncodedWords(String s) {
        Matcher m = MIME_WORD.matcher(s);
        StringBuffer sb = new StringBuffer();

        while (m.find()) {
            String charsetName = m.group(1);
            String enc = m.group(2);
            String payload = m.group(3);

            Charset cs;
            try {
                cs = Charset.forName(charsetName);
            } catch (Exception e) {
                cs = StandardCharsets.UTF_8;
            }

            String decoded = m.group(0);
            try {
                if (enc.equalsIgnoreCase("B")) {
                    byte[] bytes = Base64.getDecoder().decode(payload);
                    decoded = new String(bytes, cs);
                } else { // Q
                    decoded = decodeMimeQ(payload, cs);
                }
            } catch (Exception ignore) {
                // 실패하면 원본 유지
            }

            m.appendReplacement(sb, Matcher.quoteReplacement(decoded));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String decodeMimeQ(String payload, Charset cs) {
        // RFC 2047 Q-encoding: '_' -> ' ', '=HH' hex
        String q = payload.replace('_', ' ');
        byte[] bytes = decodeQuotedPrintableBytes(q);
        return new String(bytes, cs);
    }

    private static String maybeDecodeBase64ToText(String s) {
        String trimmed = s.trim();

        // 너무 짧으면 base64일 확률 낮음
        if (trimmed.length() < 16) return s;

        // MIME-word 자체는 여기서 또 디코드하지 않도록
        if (trimmed.contains("=?") && trimmed.contains("?=")) return s;

        // base64 char셋만으로 구성되어 있으면 시도
        if (!BASE64ISH.matcher(trimmed).matches()) return s;

        try {
            byte[] decoded = Base64.getMimeDecoder().decode(trimmed);
            String asUtf8 = new String(decoded, StandardCharsets.UTF_8);

            // “진짜 텍스트”로 보이면 교체
            if (looksLikeText(asUtf8)) return asUtf8;

            // UTF-8 아니면 ISO-8859-1도 한 번
            String asLatin1 = new String(decoded, StandardCharsets.ISO_8859_1);
            if (looksLikeText(asLatin1)) return asLatin1;

        } catch (Exception ignore) {
            // not base64
        }
        return s;
    }

    private static boolean looksLikeText(String s) {
        if (s == null || s.isBlank()) return false;

        int len = s.length();
        int bad = 0;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            // 허용: 일반문자, 공백류, 개행/탭
            if (c == '\r' || c == '\n' || c == '\t') continue;
            if (c < 0x20) bad++;
        }
        // 제어문자가 너무 많으면 “텍스트”가 아님
        return bad <= Math.max(2, len / 50);
    }

    private static String decodeQuotedPrintableText(String s) {
        // 너무 공격적으로 하면 base64나 다른 포맷까지 망가질 수 있어서,
        // "=HH"가 어느 정도 있는 경우만 디코드 시도
        if (s.indexOf('=') < 0) return s;

        try {
            byte[] bytes = decodeQuotedPrintableBytes(s);
            String out = new String(bytes, StandardCharsets.UTF_8);
            // 디코드 결과가 텍스트 같으면 교체
            return looksLikeText(out) ? out : s;
        } catch (Exception ignore) {
            return s;
        }
    }

    private static byte[] decodeQuotedPrintableBytes(String s) {
        // soft line break "=\\r\\n" 제거
        String in = s.replace("=\r\n", "").replace("=\n", "");

        ByteArrayOutputStream baos = new ByteArrayOutputStream(in.length());
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if (c == '=' && i + 2 < in.length()) {
                int hi = hex(in.charAt(i + 1));
                int lo = hex(in.charAt(i + 2));
                if (hi >= 0 && lo >= 0) {
                    baos.write((hi << 4) + lo);
                    i += 2;
                    continue;
                }
            }
            baos.write((byte) c);
        }
        return baos.toByteArray();
    }

    private static int hex(char c) {
        if ('0' <= c && c <= '9') return c - '0';
        if ('a' <= c && c <= 'f') return 10 + (c - 'a');
        if ('A' <= c && c <= 'F') return 10 + (c - 'A');
        return -1;
    }

    /** ===== 디버그 ===== */
    private static String summarize(JsonNode root) {
        if (root == null) return "null";

        JsonNode items = root.get("items");
        if (items == null || !items.isArray()) return "no items";

        int n = Math.min(items.size(), 5);
        StringBuilder sb = new StringBuilder();
        sb.append("items=").append(items.size()).append(", sample=").append(n).append("\n");

        for (int i = 0; i < n; i++) {
            JsonNode item = items.get(i);
            String subject = decodeAll(headerFirst(item, "Subject"));
            String body = decodeAll(safeText(item.at("/Content/Body")));
            String preview = body == null ? "" : body.replace("\r", "").replace("\n", " ");
            if (preview.length() > 120) preview = preview.substring(0, 120) + "...";

            sb.append("- subject=").append(subject)
              .append(", to=").append(recipientsToString(item))
              .append(", bodyPreview=").append(preview)
              .append("\n");
        }
        return sb.toString();
    }

    private static String recipientsToString(JsonNode item) {
        JsonNode toArr = item.get("To");
        if (toArr == null || !toArr.isArray() || toArr.size() == 0) return "[]";

        List<String> r = new ArrayList<>();
        for (JsonNode n : toArr) {
            String addr = mailboxDomain(n);
            if (addr != null) r.add(addr);
        }
        return r.toString();
    }
}
