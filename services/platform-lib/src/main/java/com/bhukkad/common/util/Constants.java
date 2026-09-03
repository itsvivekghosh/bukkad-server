package com.bhukkad.common.util;

public class Constants {

    // Order related
    public static final int DEFAULT_DELIVERY_TIME = 30; // minutes
    public static final double DEFAULT_DELIVERY_FEE = 40.0;
    public static final double TAX_RATE = 0.05; // 5%

    // Pagination
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    // Loyalty points
    public static final int POINTS_PER_HUNDRED = 1;
    public static final int POINTS_TO_RUPEE_RATIO = 10;

    // Distance
    public static final double MAX_DELIVERY_DISTANCE_KM = 10.0;

    /**
     * Small epsilon added to the radius when checking proximity, to make the
     * boundary inclusive across different Haversine implementations (Redis GEO
     * vs MySQL SQL). Redis and MySQL may round differently at the exact
     * boundary due to floating-point precision, causing one to include and
     * the other to exclude a restaurant exactly at the radius distance.
     * 10 meters is small enough to be imperceptible to users but large enough
     * to absorb cross-implementation floating-point drift.
     */
    public static final double PROXIMITY_RADIUS_EPSILON_KM = 0.01;

    // Rating
    public static final int MIN_RATING = 1;
    public static final int MAX_RATING = 5;

    // File upload
    public static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB
    public static final String[] ALLOWED_IMAGE_TYPES = {"image/jpeg", "image/png", "image/jpg"};

    // OTP
    public static final int OTP_EXPIRY_MINUTES = 10;
    public static final int OTP_LENGTH = 6;

    // Messages
    public static final String SUCCESS_MESSAGE = "Operation completed successfully";
    public static final String FAILURE_MESSAGE = "Operation failed";
    public static final String UNAUTHORIZED_MESSAGE = "You are not authorized to perform this action";
    public static final String NOT_FOUND_MESSAGE = "Resource not found";

    private Constants() {
        // Private constructor to prevent instantiation
    }
}