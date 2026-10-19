package com.omniretail.backend.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * IP del cliente para limites y cuotas. {@code X-Forwarded-For} solo se acepta cuando la conexion llega de un
 * proxy propio (loopback o red privada, p. ej. Caddy o el contenedor de Next.js); en produccion Caddy
 * reemplaza ese encabezado con la IP remota. Si alguien alcanza el backend directamente, el encabezado lo
 * controla el navegador y se ignora: se usa la direccion del socket.
 */
public final class ClientAddressResolver {

    private ClientAddressResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank() || !isTrustedProxy(request.getRemoteAddr())) {
            return request.getRemoteAddr();
        }
        int comma = forwarded.indexOf(',');
        String first = forwarded.substring(0, comma >= 0 ? comma : forwarded.length()).trim();
        if (!first.matches("[0-9a-fA-F:.]+")) {
            return request.getRemoteAddr();
        }
        try {
            return InetAddress.getByName(first).getHostAddress();
        } catch (UnknownHostException ex) {
            return request.getRemoteAddr();
        }
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
