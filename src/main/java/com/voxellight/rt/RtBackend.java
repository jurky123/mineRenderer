package com.voxellight.rt;
/** Backend lifecycle/capability boundary; a future primary-ray integrator does not own native UI. */
public interface RtBackend extends AutoCloseable {
 RtTraversalBackend traversal();
 boolean active();
 String status();
 @Override void close();
}
