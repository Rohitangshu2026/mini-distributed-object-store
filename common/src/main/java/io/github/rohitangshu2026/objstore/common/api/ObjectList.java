package io.github.rohitangshu2026.objstore.common.api;

import java.util.List;

/**
 * One page of committed objects: {@code GET /api/v1/objects}.
 *
 * @param objects the page, ordered by object ID, which is creation order
 * @param limit the most objects a page holds
 * @param offset how many objects come before this page
 * @param total how many committed objects there are
 */
public record ObjectList(List<ObjectListEntry> objects, int limit, int offset, long total) {}
