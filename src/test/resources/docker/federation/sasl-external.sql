-- Description: servers authenticate to each other with SASL EXTERNAL
-- STARTTLS stays enabled, so the servers present the certificates of the federation environment, and dialback is
-- disabled, so that SASL EXTERNAL is the only way in which they can authenticate.
INSERT INTO ofProperty (name, propValue)
VALUES ('xmpp.server.dialback.enabled', 'false')
    ON CONFLICT (name) DO UPDATE SET propValue = 'false';

-- RFC 6120 requires the receiving server to send a new stream ID when the stream is restarted after TLS and after SASL
-- (§ 4.3.3, § 5.4.3.3, § 6.4.6). With this, a server refuses a restarted stream that reuses the previous stream ID, so
-- federation only works when the receiving server sends a new one. Versions of Openfire without this setting ignore it.
INSERT INTO ofProperty (name, propValue)
VALUES ('xmpp.server.outgoing.accept-reused-stream-id', 'false')
    ON CONFLICT (name) DO UPDATE SET propValue = 'false';
