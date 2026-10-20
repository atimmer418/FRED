# Paycheck Detection and Webhook System

This branch implements a comprehensive system for automatically detecting user paychecks in Plaid and setting up webhooks to trigger recurring investments.

## Overview

The system works in the following stages:

1. **User Configuration**: Users enter their paycheck details on the survey page (name, employer, frequency, expected amount)
2. **Daily Detection Job**: A scheduled job runs daily to check for recurring transactions that match user configurations
3. **Paycheck Matching**: Using sophisticated matching logic based on frequency, amount, and employer name
4. **Webhook Setup**: Once a paycheck is detected, webhooks are configured to trigger on future paycheck deposits
5. **Investment Triggering**: When new paycheck transactions are detected via webhook, automatic investments are triggered (to be implemented in future branch)

## Key Components

### 1. UserPaycheckConfig Model
Enhanced with new fields for tracking detection status:
- `paycheckDetected`: Whether this paycheck has been found in Plaid
- `webhookConfigured`: Whether webhook is set up for this paycheck  
- `plaidStreamId`: The Plaid transaction stream ID
- `lastDetectionAttempt`: When we last tried to detect this paycheck
- `paycheckDetectedAt`: When the paycheck was successfully detected
- `webhookConfiguredAt`: When the webhook was configured

### 2. PaycheckDetectionService
Core service responsible for:
- **Scheduled Detection**: `@Scheduled(cron = "0 0 2 * * ?")` - runs daily at 2 AM
- **Manual Detection**: Can be triggered via API endpoint for specific users
- **Matching Logic**: Sophisticated algorithm to match user configs with Plaid recurring transactions
- **Webhook Setup**: Configures webhooks for detected paychecks

#### Matching Algorithm
The system uses a scoring-based approach to match paychecks:
- **Frequency Match**: Direct comparison of pay frequencies (WEEKLY, BIWEEKLY, etc.)
- **Amount Match**: Within 20% tolerance of expected amount
- **Name/Employer Match**: Fuzzy matching of employer names
- **Account Match**: Direct account ID comparison (if available)

Requires at least 2 matches out of 3+ criteria, or 1 match for 1-2 criteria.

### 3. PaycheckDetectionController
REST API endpoints:
- `POST /api/paycheck/detect` - Manually trigger detection for authenticated user
- `GET /api/paycheck/status` - Get detection status for authenticated user
- `POST /api/paycheck/detect-all` - Admin endpoint to trigger detection for all users

### 4. Enhanced PlaidWebhookService
Updated to:
- Check for webhook-configured paycheck configs
- Process new transactions against detected paychecks
- Ready for investment triggering (placeholder implementation)

## API Usage

### Get Detection Status
```bash
GET /api/paycheck/status
Authorization: Bearer <jwt_token>
```

Response:
```json
{
  "userId": 123,
  "totalConfigs": 2,
  "detectedConfigs": 1,
  "webhookConfigs": 1,
  "allConfigsDetected": false,
  "allWebhooksConfigured": false,
  "configs": [
    {
      "id": 1,
      "name": "Primary Paycheck",
      "accountId": "account_123",
      "paycheckDetected": true,
      "webhookConfigured": true,
      "lastDetectionAttempt": "2025-09-04T10:00:00",
      "paycheckDetectedAt": "2025-09-04T10:00:00",
      "webhookConfiguredAt": "2025-09-04T10:01:00",
      "plaidStreamId": "stream_456"
    }
  ]
}
```

### Manually Trigger Detection
```bash
POST /api/paycheck/detect
Authorization: Bearer <jwt_token>
```

Response:
```json
{
  "message": "Paycheck detection completed",
  "userId": 123,
  "timestamp": "2025-09-04T10:30:00"
}
```

## Database Changes

The `UserPaycheckConfig` entity has been extended with new columns:
- `paycheck_detected` (boolean, default false)
- `webhook_configured` (boolean, default false)
- `plaid_stream_id` (varchar)
- `last_detection_attempt` (timestamp)
- `paycheck_detected_at` (timestamp)
- `webhook_configured_at` (timestamp)

## Repository Methods

New query methods added to `UserPaycheckConfigRepository`:
- `findByPaycheckDetectedFalse()` - Get all undetected configs
- `findByUserAndPaycheckDetectedFalse()` - Get undetected configs for specific user
- `findByPaycheckDetectedTrueAndWebhookConfiguredFalse()` - Get configs needing webhook setup

## Scheduling

The system uses Spring's `@Scheduled` annotation with cron expressions:
- Daily detection job: `@Scheduled(cron = "0 0 2 * * ?")` (2 AM daily)
- Requires `@EnableScheduling` in the main application class

## Future Enhancements

### Next Branch: Investment Execution
The webhook system is ready to trigger investments when paycheck deposits are detected. The next branch will implement:
- `InvestmentService` to execute trades
- Transaction matching logic in webhook handler
- Investment amount calculation based on withdrawal percentages
- User notifications for automatic investments
- Investment history tracking

### Error Handling
- Graceful handling of Plaid API rate limits
- Retry logic for failed detections
- Logging and monitoring of detection success rates
- User notifications for detection failures

### Performance Optimizations
- Batch processing for large numbers of users
- Caching of Plaid recurring transaction data
- Optimized database queries with pagination
- Background processing for webhook handling

## Testing

To test the system:

1. **Setup**: Ensure user has completed paycheck configuration in survey
2. **Manual Trigger**: Call `POST /api/paycheck/detect` endpoint
3. **Check Status**: Call `GET /api/paycheck/status` to see detection results
4. **Webhook Testing**: Simulate Plaid webhook with transaction updates

## Configuration

Add to `application.properties`:
```properties
# Scheduling configuration
spring.task.scheduling.pool.size=10
spring.task.scheduling.thread-name-prefix=paycheck-detection-

# Plaid webhook URL (if not already configured)
plaid.webhook.url=https://your-domain.com/api/webhooks/plaid
```

## Security

- All endpoints require JWT authentication except admin endpoints
- Sensitive data (access tokens) remain encrypted
- Rate limiting should be implemented for detection endpoints
- Admin endpoints should have additional role-based security

## Monitoring and Logging

The system provides comprehensive logging:
- Detection attempts and results
- Matching algorithm decisions
- Webhook configuration status
- Error conditions and recovery attempts

Use log levels:
- `INFO`: Normal operation, detection results
- `DEBUG`: Detailed matching algorithm decisions
- `WARN`: Expected issues (product not ready, no matches)
- `ERROR`: Unexpected errors requiring attention
