package com.moveinsync.roster.domain.event;

/** Where an event came from: the real-time API or a row of the nightly snapshot file. */
public enum EventSource { API, SNAPSHOT }
