/**
 * The single-writer pipeline around the matching engine.
 *
 * <p>Many threads (gateway, clock, ops) submit commands into one LMAX Disruptor ring buffer. One matching thread
 * takes them in ring order, gives each an input sequence number and applies it to the engine; that order is the
 * official history. Downstream handlers (journal in S8, then publishers) see each slot strictly after matching.
 *
 * <p>Why a ring buffer and not a queue: the ring is a fixed array of reusable slots, so publishing allocates nothing
 * and takes no lock, only a few atomic operations; consumers track their position with a sequence counter.
 *
 * <p>This package depends on the engine; the engine never depends on it.
 */
package dev.prayog.exchange.core.pipeline;
