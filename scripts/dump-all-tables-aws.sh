#!/bin/bash

# Script to dump all DynamoDB tables from AWS (production/staging)
# Requires AWS credentials to be configured

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Configuration
AWS_REGION="${AWS_REGION:-us-east-1}"
OUTPUT_DIR="${DUMP_OUTPUT_DIR:-./dynamodb-dumps-aws}"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# Check if AWS CLI is installed
if ! command -v aws &> /dev/null; then
    echo -e "${RED}Error: AWS CLI is not installed${NC}"
    exit 1
fi

# Check AWS credentials
if ! aws sts get-caller-identity &> /dev/null; then
    echo -e "${RED}Error: AWS credentials not configured${NC}"
    echo "Run: aws configure"
    exit 1
fi

# Create output directory
mkdir -p "$OUTPUT_DIR"
DUMP_DIR="$OUTPUT_DIR/dump_$TIMESTAMP"
mkdir -p "$DUMP_DIR"

echo -e "${GREEN}Dumping AWS DynamoDB tables to: $DUMP_DIR${NC}"
echo -e "${YELLOW}Region: $AWS_REGION${NC}"
echo ""

# Function to dump a single table (handles pagination)
dump_table() {
    local table_name=$1
    local output_file="$DUMP_DIR/${table_name}.json"
    
    echo -e "${YELLOW}Dumping table: $table_name${NC}"
    
    # Use scan with pagination to get all items
    local all_items="[]"
    local last_evaluated_key=""
    local page_count=0
    local total_items=0
    
    while true; do
        local scan_cmd="aws dynamodb scan --table-name \"$table_name\" --region \"$AWS_REGION\" --output json"
        
        if [ -n "$last_evaluated_key" ] && [ "$last_evaluated_key" != "null" ]; then
            scan_cmd="$scan_cmd --exclusive-start-key '$last_evaluated_key'"
        fi
        
        local scan_result=$(eval "$scan_cmd" 2>/dev/null || echo "")
        
        if [ -z "$scan_result" ]; then
            echo -e "${RED}  Failed to scan $table_name${NC}"
            return 1
        fi
        
        local items=$(echo "$scan_result" | jq -r '.Items // []')
        local count=$(echo "$scan_result" | jq -r '.Count // 0')
        last_evaluated_key=$(echo "$scan_result" | jq -r '.LastEvaluatedKey // empty')
        
        if [ "$count" -gt 0 ]; then
            ((page_count++))
            total_items=$((total_items + count))
            all_items=$(echo "$all_items" | jq ". + $items")
            echo -e "  Page $page_count: $count items (total: $total_items)"
        fi
        
        if [ -z "$last_evaluated_key" ] || [ "$last_evaluated_key" = "null" ]; then
            break
        fi
    done
    
    # Save all items
    echo "$all_items" | jq '.' > "$output_file"
    
    local file_size=$(du -h "$output_file" | cut -f1)
    
    if [ "$total_items" -gt 0 ]; then
        echo -e "${GREEN}  ✓ Dumped $total_items items in $page_count page(s) ($file_size)${NC}"
    else
        echo -e "${YELLOW}  ⚠ Table is empty${NC}"
    fi
}

# List all tables
echo -e "${YELLOW}Listing all tables in region $AWS_REGION...${NC}"

TABLES=$(aws dynamodb list-tables --region "$AWS_REGION" --output json 2>/dev/null | jq -r '.TableNames[]' || echo "")

if [ -z "$TABLES" ]; then
    echo -e "${RED}No tables found${NC}"
    exit 1
fi

TABLE_COUNT=$(echo "$TABLES" | wc -l | tr -d ' ')
echo -e "${GREEN}Found $TABLE_COUNT table(s)${NC}"
echo ""

# Dump each table
SUCCESS_COUNT=0
FAILED_COUNT=0

for table in $TABLES; do
    if dump_table "$table"; then
        ((SUCCESS_COUNT++))
    else
        ((FAILED_COUNT++))
    fi
done

echo ""
echo -e "${GREEN}=== Dump Summary ===${NC}"
echo -e "Total tables: $TABLE_COUNT"
echo -e "${GREEN}Successfully dumped: $SUCCESS_COUNT${NC}"
if [ "$FAILED_COUNT" -gt 0 ]; then
    echo -e "${RED}Failed: $FAILED_COUNT${NC}"
fi
echo -e "Output directory: $DUMP_DIR"
echo ""

# Create summary
SUMMARY_FILE="$DUMP_DIR/SUMMARY.txt"
cat > "$SUMMARY_FILE" << EOF
AWS DynamoDB Table Dump Summary
================================
Timestamp: $(date)
Region: $AWS_REGION
Output Directory: $DUMP_DIR

Tables Found: $TABLE_COUNT
Successfully Dumped: $SUCCESS_COUNT
Failed: $FAILED_COUNT

Tables:
$(echo "$TABLES" | nl -w2 -s'. ')
EOF

echo -e "${GREEN}Summary saved to: $SUMMARY_FILE${NC}"
echo -e "${GREEN}✓ Dump complete!${NC}"

