package com.moveinsync.roster.domain.model;

/**
 * Who deactivated an employee. It matters for reactivation: a deactivation by the nightly
 * reconciliation is only "evidence of absence" and is undone by any HR record at the same or a
 * newer version; a deactivation by an HR event is undone only by a newer CREATE (rehire).
 */
public enum DeactivatedBy { HR, RECONCILIATION }
