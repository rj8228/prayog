package dev.prayog.exchange.app.ws;

/** Sent every few seconds on an otherwise quiet socket, so proxies keep it open and clients can detect a dead one. */
record Heartbeat(String type, long simTime) {}
