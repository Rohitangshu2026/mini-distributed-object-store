package io.github.rohitangshu2026.objstore.controller.metadata;

import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.NOW;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.chunk;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.committed;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.objectId;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.uploading;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Tests {@link ObjectRepository} against a real SQLite file. */
class ObjectRepositoryIT extends SqliteTestBase {

  @Autowired ObjectRepository objects;

  @Test
  void uploadingObjectReadsBackWithItsTotalsUnknown() {
    ObjectRow object = uploading(objectId(1));

    objects.insert(object);

    assertThat(objects.find(object.objectId())).contains(object);
  }

  @Test
  void committedObjectReadsBackIdentical() {
    ObjectRow object = committed(objectId(2), 3);

    objects.insert(object);

    assertThat(objects.find(object.objectId())).contains(object);
  }

  @Test
  void unknownObjectIsNotFound() {
    assertThat(objects.find(objectId(99))).isEmpty();
  }

  @Test
  void updateCustomMetadataReplacesItAndRecordsWhen() {
    ObjectRow object = committed(objectId(3), 1);
    objects.insert(object);

    assertThat(objects.updateCustomMetadata(object.objectId(), "{\"team\":\"infra\"}", NOW + 5))
        .isTrue();

    assertThat(objects.find(object.objectId()))
        .hasValueSatisfying(row -> {
          assertThat(row.customMetadata()).isEqualTo("{\"team\":\"infra\"}");
          assertThat(row.updatedAt()).isEqualTo(NOW + 5);
          assertThat(row.createdAt()).isEqualTo(object.createdAt());
        });
  }

  @Test
  void updateOfUnknownObjectChangesNothing() {
    assertThat(objects.updateCustomMetadata(objectId(98), "{}", NOW)).isFalse();
  }

  @Test
  void chunksComeBackInIndexOrder() {
    String id = objectId(4);
    objects.insert(uploading(id));
    objects.insertChunk(chunk(id, 2));
    objects.insertChunk(chunk(id, 0));
    objects.insertChunk(chunk(id, 1));

    assertThat(objects.chunksOf(id)).containsExactly(chunk(id, 0), chunk(id, 1), chunk(id, 2));
  }
}
