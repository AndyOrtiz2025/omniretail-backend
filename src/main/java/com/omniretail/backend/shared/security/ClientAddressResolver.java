package com.omniretail.backend.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * IP del cliente para limites y cuotas. {@code X-Forwarded-For} solo se acepta cuando la conexion llega de un
 * proxy propio (loopback o red privada, p. ej. Caddy o el contenedor de Next.js); en produccion Caddy
 * reemplaza ese encabezado con la IP remota. Si alguien alcanza el backend directamente, el encabezado lo
 * controla el navegador y se ignora: se usa la direccion del socket.
 */
public final class ClientAddressResolver {

    private static final Pattern IPV4 =
            Pattern.compile("((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");

    private ClientAddressResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank() || !isTrustedProxy(request.getRemoteAddr())) {
            return request.getRemoteAddr();
        }
        int comma = forwarded.indexOf(',');
        String first = forwarded.substring(0, comma >= 0 ? comma : forwarded.length()).trim();
        if (!isIpLiteral(first)) {
            return request.getRemoteAddr();
        }
        try {
            return InetAddress.getByName(first).getHostAddress();
        } catch (UnknownHostException ex) {
            return request.getRemoteAddr();
        }
    }

    /**
     * Solo literales: IPv4 con octetos validos o IPv6 (lleva ':'). Asi {@code getByName} nunca resuelve por DNS
     * un nombre como {@code bad.cafe}, que solo tiene caracteres hexadecimales.
     */
    private static boolean isIpLiteral(String value) {
        return IPV4.matcher(value).matches() || (value.indexOf(':') >= 0 && value.matches("[0-9a-fA-F:.]+"));
    }

    private static boolean isTrustedProxy(String remoteAddress) {
        try {
            InetAddress address = InetAddress.getByName(remoteAddress);
            return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress();
        } catch (UnknownHostException ex) {
            return false;
        }
    }
}
