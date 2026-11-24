-- Description: Stream Management (XEP-0198) with resumption on server-to-server connections, for both servers
-- Openfire versions without Stream Management on server-to-server connections ignore these properties.
-- FederatedTestEnvironment adds a DNS override to each server's copy, so that the server reaches the other through the
-- relay, which a test can make drop what it relays, and whose connections it can close to break them without closing
-- their streams.

INSERT INTO ofProperty (name, propValue)
VALUES ('stream.management.server.active', 'true')
    ON CONFLICT (name) DO UPDATE SET propValue = 'true';

INSERT INTO ofProperty (name, propValue)
VALUES ('stream.management.server.resume.enabled', 'true')
    ON CONFLICT (name) DO UPDATE SET propValue = 'true';
