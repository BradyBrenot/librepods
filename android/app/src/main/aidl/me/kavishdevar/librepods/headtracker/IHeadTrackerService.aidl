package me.kavishdevar.librepods.headtracker;

interface IHeadTrackerService {
    void start(String macAddress) = 1;
    oneway void updatePose(in float[] rotationVector, in float[] angularVelocity, int discontinuity) = 2;
    void recenter() = 3;
    void stop() = 4;

    /** Shizuku calls this when the user service is replaced or unbound. */
    void destroy() = 16777114;
}
