-- Flash Sale Database Initialization Script
-- This script runs automatically when PostgreSQL container starts

-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Create indexes for better performance (tables will be created by JPA)
-- These will be created after application starts, but we can prepare for them

-- Grant all privileges to the application user
GRANT ALL PRIVILEGES ON DATABASE flashsale TO flashsale_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO flashsale_user;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO flashsale_user;

-- Set timezone to IST (India Standard Time)
SET timezone = 'Asia/Kolkata';

-- Log initialization
DO $$
BEGIN
    RAISE NOTICE 'Flash Sale Database Initialized Successfully';
    RAISE NOTICE 'Database: flashsale';
    RAISE NOTICE 'User: flashsale_user';
    RAISE NOTICE 'Timezone: %', current_setting('timezone');
END $$;
