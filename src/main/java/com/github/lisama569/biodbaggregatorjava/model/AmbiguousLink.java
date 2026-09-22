package com.github.lisama569.biodbaggregatorjava.model;

/**
 * A cross-reference assertion that did NOT cause a merge, recorded so the ambiguity is
 * never resolved silently. Happens when the referenced token is unknown (dangling
 * reference) or when two different-typed entities claim the same namespaced id.
 *
 * @param token       namespaced token, e.g. "GENE|uniprot:Q9Y6"
 * @param reason       DANGLING_REFERENCE or CROSS_TYPE_COLLISION
 * @param ownerSource  source that already owned the token (null for dangling)
 * @param ownerId      local id of the owner (null for dangling)
 * @param claimerSource source making the non-merging assertion
 * @param claimerId    local id of the claiming record
 */
public record AmbiguousLink(
        String token,
        String reason,
        String ownerSource,
        String ownerId,
        String claimerSource,
        String claimerId
) {
    public static final String DANGLING_REFERENCE = "DANGLING_REFERENCE";
    public static final String CROSS_TYPE_COLLISION = "CROSS_TYPE_COLLISION";
}
