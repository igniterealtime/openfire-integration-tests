-- Description: STARTTLS disabled for S2S connections
-- Without TLS, the servers authenticate to each other with dialback. The run with SASL EXTERNAL uses
-- sasl-external.sql instead.
INSERT INTO ofProperty (name, propValue)
VALUES ('xmpp.server.tls.policy', 'disabled')
    ON CONFLICT (name) DO UPDATE SET propValue = 'disabled';
