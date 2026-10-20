# Testing Paycheck Detection System

Since there's a JVM version issue preventing compilation, here's how you can test the paycheck detection system once you resolve the Java version:

## Prerequisites

1. Fix Java version (need Java 17+ for Spring Boot 3.5.0)
2. Start the backend: `./gradlew bootRun`
3. Have a user with paycheck configurations in the database

## Testing Steps

### 1. Check User's Paycheck Configuration Status

```bash
# First, authenticate as a user (use the dev auth endpoint)
curl -X POST https://api-dev.fredvested.com/api/dev/authenticate-as-user \
  -H "Content-Type: application/json" \
  -d '{"email": "beastmode@gmail.com"}'
```

This should return a JWT token. Use this token for subsequent requests.

### 2. Check Paycheck Detection Status

```bash
# Get current detection status
curl -X GET https://api-dev.fredvested.com/api/paycheck/status \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE"
```

Expected response:
```json
{
  "userId": 1,
  "totalConfigs": 1,
  "detectedConfigs": 0,
  "webhookConfigs": 0,
  "allConfigsDetected": false,
  "allWebhooksConfigured": false,
  "configs": [
    {
      "id": 1,
      "name": "Primary Paycheck",
      "accountId": "primary_income",
      "paycheckDetected": false,
      "webhookConfigured": false,
      "lastDetectionAttempt": null,
      "paycheckDetectedAt": null,
      "webhookConfiguredAt": null,
      "plaidStreamId": null
    }
  ]
}
```

### 3. Manually Trigger Paycheck Detection

```bash
# Trigger detection for the authenticated user
curl -X POST https://api-dev.fredvested.com/api/paycheck/detect \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE"
```

Expected response:
```json
{
  "message": "Paycheck detection completed",
  "userId": 1,
  "timestamp": "2025-09-04T15:30:00"
}
```

### 4. Check Status Again After Detection

```bash
# Check if anything was detected
curl -X GET https://api-dev.fredvested.com/api/paycheck/status \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE"
```

Look for changes in:
- `lastDetectionAttempt` should be updated
- `paycheckDetected` might be true if a match was found
- `paycheckDetectedAt` will have a timestamp if detected
- `plaidStreamId` will have a value if detected

### 5. Test Database Directly

You can also check the database directly:

```sql
-- Check paycheck configurations
SELECT 
    id, 
    name, 
    employer_name, 
    expected_amount, 
    frequency,
    paycheck_detected,
    webhook_configured,
    last_detection_attempt,
    paycheck_detected_at,
    plaid_stream_id
FROM user_paycheck_configs;
```

## What to Expect

### First Run (No Plaid Data Ready)
- `paycheckDetected` will remain `false`
- `lastDetectionAttempt` will be updated
- Logs should show "No recurring income found" or "PRODUCT_NOT_READY"

### After Plaid Data is Ready
- The detection algorithm will try to match user configs with Plaid recurring transactions
- If a match is found:
  - `paycheckDetected` becomes `true`
  - `paycheckDetectedAt` gets timestamp
  - `webhookConfigured` becomes `true`
  - `webhookConfiguredAt` gets timestamp
  - `plaidStreamId` gets the Plaid stream identifier

### Matching Criteria
The system matches based on:
1. **Frequency**: WEEKLY, BIWEEKLY, SEMI_MONTHLY, MONTHLY
2. **Amount**: Within 20% of expected amount
3. **Employer Name**: Fuzzy matching with paycheck description

## Troubleshooting

### Common Issues

1. **No recurring data**: Plaid needs time to analyze transactions
2. **No matches found**: User config doesn't match Plaid data closely enough
3. **Authentication errors**: JWT token expired or invalid

### Debug Logs

Check the application logs for:
```
[PaycheckDetectionService] Processing paycheck detection for config ID: X
[PaycheckDetectionService] Found matching paycheck: [name]
[PaycheckDetectionService] No matching paycheck found for config ID X yet
```

### Manual Database Testing

You can manually set detection status for testing:

```sql
-- Manually mark a paycheck as detected for testing
UPDATE user_paycheck_configs 
SET 
    paycheck_detected = true,
    webhook_configured = true,
    paycheck_detected_at = NOW(),
    webhook_configured_at = NOW(),
    plaid_stream_id = 'test_stream_123'
WHERE id = 1;
```

## Advanced Testing

### Test the Scheduled Job

```bash
# Trigger detection for all users (admin endpoint)
curl -X POST https://api-dev.fredvested.com/api/paycheck/detect-all
```

### Test with Mock Data

Create test paycheck configurations with known data:

```sql
INSERT INTO user_paycheck_configs (
    user_id, account_id, name, withdrawal_percentage, 
    employer_name, expected_amount, frequency
) VALUES (
    1, 'test_account', 'Test Paycheck', 0.10,
    'Test Company', 3000.00, 'BIWEEKLY'
);
```

### Webhook Testing

Once paycheck detection works, you can test webhook handling by:

1. Setting up ngrok for local webhook testing
2. Configuring Plaid webhooks to point to your endpoint
3. Making test transactions in Plaid Sandbox
4. Observing webhook processing in logs

## Expected Log Output

Successful detection:
```
[PaycheckDetectionService] Starting daily paycheck detection job
[PaycheckDetectionService] Found 1 undetected paycheck configurations to process
[PaycheckDetectionService] Processing paycheck detection for config ID: 1 (user: 1)
[PaycheckDetectionService] Successfully matched paycheck config ID 1 with Plaid stream for account account_123
[PaycheckDetectionService] Webhook configured for paycheck config ID: 1
[PaycheckDetectionService] Completed daily paycheck detection job
```

No matches found:
```
[PaycheckDetectionService] No recurring income found for user 1, paycheck detection not ready yet
[PaycheckDetectionService] No matching paycheck found for config ID 1 yet
```

This comprehensive testing approach will help you verify that the paycheck detection system is working correctly!
