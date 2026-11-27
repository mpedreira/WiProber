nimWifi configuration ZIP (example)
===================================

Open this ZIP with nimWifi (from a file manager, or Imported configuration > Import file).
nimWifi shows a preview of every destination and method before anything is activated.

Contents
--------
wiprober-config.json  Throughput servers, survey guide, roaming probe, network tests,
                      HTTP scripts and credentials. Keys starting with "_" are comments.
stock-check.har       A browser recording (Developer tools > Network > Save all as HAR).
                      Imported as the script "stock-check": only GET/HEAD requests of
                      documents, XHR and fetch; cookies and session headers are dropped;
                      the Authorization header and the access_token parameter become the
                      credentials har_Authorization and har_access_token, to be set on the
                      phone (Imported configuration > Credentials).
README.txt            This file (ignored by the import).

Every host is fictitious (example.com). Replace them with yours.
