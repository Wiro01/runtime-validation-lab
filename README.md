# Windows CI Harness

Generic public validation utilities only.

This repository must never receive proprietary source, private payloads, credentials, production configuration, private binaries, personal identifiers, or sensitive logs.

Security rules:
- public runner input must be public and harmless only;
- no private storage credentials are permitted;
- no secret-backed transport is permitted;
- no proprietary source may be fetched or compiled here;
- no Actions artifacts or caches may contain private material;
- prohibited file extensions and sensitive fingerprints fail closed;
- repository history must remain free of private material.

Private build work belongs on a trusted private machine or private worker outside this public repository.
