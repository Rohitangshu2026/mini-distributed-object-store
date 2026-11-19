package io.github.rohitangshu2026.objstore.controller.object;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The objects that an upload or delete request is working on right now. Recovery skips them, so it
 * can never abort an upload that is still running; anything else that is UPLOADING or DELETING was
 * abandoned by a crash or a failed abort.
 */
@Component
public class InFlightObjects {

  private final Set<String> objectIds = ConcurrentHashMap.newKeySet();

  /**
   * Marks an object as being worked on. Called before the object's first transaction.
   *
   * @param objectId the object's ID
   */
  public void add(String objectId) {
    objectIds.add(objectId);
  }

  /**
   * Marks an object as no longer being worked on.
   *
   * @param objectId the object's ID
   */
  public void remove(String objectId) {
    objectIds.remove(objectId);
  }

  /**
   * {@return whether a request is working on the object}
   *
   * @param objectId the object's ID
   */
  public boolean contains(String objectId) {
    return objectIds.contains(objectId);
  }
}
