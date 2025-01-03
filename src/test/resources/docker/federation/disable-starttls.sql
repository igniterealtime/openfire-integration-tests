-- Description: STARTTLS disabled for S2S connections
-- This is required as a bug is preventing our self-signed certificates from being used for S2S connections
INSERT INTO ofProperty (name, propValue)
VALUES ('xmpp.server.tls.policy', 'disabled')
    ON CONFLICT (name) DO UPDATE SET propValue = 'disabled';