# Reminder Notification Logic

## Overview
This document explains when reminder notifications are sent for transaction actions.

## Reminder Behavior

### Case 1: Both Due Date and Reminder Date Present
- **Reminder is sent at the `reminderDate` time**
- `reminderDate` should ideally be before or equal to `dueDate`
- If `reminderDate` is after `dueDate`, reminder is still sent at `reminderDate` (user's choice)
- A warning is logged if `reminderDate` is after `dueDate`

**Example:**
- Due Date: `2024-12-31T23:59:59Z`
- Reminder Date: `2024-12-30T10:00:00Z`
- **Reminder sent at:** `2024-12-30T10:00:00Z` ✅

### Case 2: Only Reminder Date Present (No Due Date)
- **Reminder is sent at the `reminderDate` time**
- No due date validation needed
- This is useful for reminders that don't have a specific due date

**Example:**
- Reminder Date: `2024-12-30T10:00:00Z`
- Due Date: `null`
- **Reminder sent at:** `2024-12-30T10:00:00Z` ✅

### Case 3: Only Due Date Present (No Reminder Date)
- **No reminder is sent**
- User must set `reminderDate` to receive reminders
- The action will still show as overdue if `dueDate` passes

**Example:**
- Due Date: `2024-12-31T23:59:59Z`
- Reminder Date: `null`
- **Reminder sent:** None ❌

## Implementation

### iOS App (Primary)
- Reminders are scheduled client-side using `UNUserNotificationCenter`
- Scheduled when action is created/updated if `reminderDate` is in the future
- Rescheduled when actions are loaded from backend
- Notification is sent at the exact `reminderDate` time

### Backend Service (Backup/Fallback)
- `ReminderNotificationService` runs every hour via `@Scheduled` cron job
- Currently a placeholder - requires GSI on `reminderDate` for production use
- iOS app handles reminders, so backend service is a backup
- Future enhancement: Add GSI on `reminderDate` to enable efficient querying

## Validation

### When Creating/Updating Actions
- If both `reminderDate` and `dueDate` are present:
  - Validation checks if `reminderDate` is after `dueDate`
  - If so, a warning is logged but the action is still created/updated
  - This allows users to set reminders after due dates if needed

## Date Formats

### Supported Formats
- ISO DateTime: `2024-12-30T10:00:00Z` (with timezone)
- ISO DateTime: `2024-12-30T10:00:00` (without timezone, treated as UTC)
- ISO Date: `2024-12-30` (treated as start of day in UTC)

## Future Enhancements

1. **GSI on ReminderDate**: Add Global Secondary Index on `reminderDate` to enable efficient querying
2. **Reminder Queue Table**: Create separate table for pending reminders
3. **DynamoDB Streams**: Use streams to process reminders in real-time
4. **Multiple Reminders**: Support multiple reminder dates per action
5. **Recurring Reminders**: Support recurring reminders for recurring actions

