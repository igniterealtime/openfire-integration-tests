-- Description: Stream Management (XEP-0198) with resumption on server-to-server connections, for xmpp1
-- Openfire versions without Stream Management on server-to-server connections ignore these properties.

INSERT INTO ofProperty (name, propValue)
VALUES ('stream.management.server.active', 'true')
    ON CONFLICT (name) DO UPDATE SET propValue = 'true';

INSERT INTO ofProperty (name, propValue)
VALUES ('stream.management.server.resume.enabled', 'true')
    ON CONFLICT (name) DO UPDATE SET propValue = 'true';

-- The connections that this server opens to xmpp2 go through the relay container (172.50.0.30, port 5269), which a
-- test can freeze and restart to break a connection without closing its stream.
INSERT INTO ofProperty (name, propValue)
VALUES ('dnsutil.dnsOverride', '{xmpp2.localhost.example,172.50.0.30:5269}')
    ON CONFLICT (name) DO UPDATE SET propValue = '{xmpp2.localhost.example,172.50.0.30:5269}';
