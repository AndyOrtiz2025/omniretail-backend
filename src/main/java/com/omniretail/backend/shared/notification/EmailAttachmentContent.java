package com.omniretail.backend.shared.notification;

import java.util.Objects;

/** Adjunto ya resuelto (bytes en memoria). Nunca se persiste ni se registra en logs. */
public record EmailAttachmentContent(String filename, String mediaType, byte[] bytes) {

    public EmailAttachmentContent {
        Objects.requireNonNull(filename, "filename");
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(bytes, "bytes");
    }

    @Override
    public String toString() {
        return "EmailAttachmentContent[filename=" + filename + ", mediaType=" + mediaType
                + ", size=" + bytes.length + "]";
    }
}
