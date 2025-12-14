#!/bin/bash

# Script to dump all DynamoDB tables with all fields and rows
# Works with both LocalStack (local) and AWS DynamoDB (production)

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Configuration
ENDPOINT_URL="${DYNAMODB_ENDPOINT:-http://localhost:4566}"  # Default to LocalStack
AWS_REGION="${AWS_REGION:-us-east-1}"  # Required by AWS CLI (even for LocalStack)
OUTPUT_DIR="${DUMP_OUTPUT_DIR:-./dynamodb-dumps}"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# Check if AWS CLI is installed
if ! command -v aws &> /dev/null; then
    echo -e "${RED}Error: AWS CLI is not installed${NC}"
    echo "Install it with: brew install awscli (macOS) or pip install awscli"
    exit 1
fi

# Detect if we're using LocalStack or AWS
if [[ "$ENDPOINT_URL" == *"localhost"* ]] || [[ "$ENDPOINT_URL" == *"127.0.0.1"* ]]; then
    USE_LOCALSTACK=true
    echo -e "${GREEN}Using LocalStack at: $ENDPOINT_URL${NC}"
    
    # Set dummy credentials for LocalStack (AWS CLI requires credentials even for LocalStack)
    export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}"
    export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}"
    export AWS_DEFAULT_REGION="${AWS_REGION:-us-east-1}"
else
    USE_LOCALSTACK=false
    echo -e "${GREEN}Using AWS DynamoDB${NC}"
    
    # Check AWS credentials for real AWS
    if ! aws sts get-caller-identity &> /dev/null; then
        echo -e "${RED}Error: AWS credentials not configured${NC}"
        echo "Run: aws configure"
        exit 1
    fi
fi

# Create output directory
mkdir -p "$OUTPUT_DIR"
DUMP_DIR="$OUTPUT_DIR/dump_$TIMESTAMP"
mkdir -p "$DUMP_DIR"

echo -e "${GREEN}Dumping DynamoDB tables to: $DUMP_DIR${NC}"
echo ""

# Function to dump a single table
dump_table() {
    local table_name=$1
    local output_file="$DUMP_DIR/${table_name}.json"
    
    echo -e "${YELLOW}Dumping table: $table_name${NC}"
    
    if [ "$USE_LOCALSTACK" = true ]; then
        # Use LocalStack endpoint
        aws dynamodb scan \
            --table-name "$table_name" \
            --endpoint-url "$ENDPOINT_URL" \
            --region "$AWS_REGION" \
            --output json > "$output_file" 2>/dev/null || {
            echo -e "${RED}  Failed to dump $table_name (table may not exist)${NC}"
            rm -f "$output_file"
            return 1
        }
    else
        # Use AWS DynamoDB
        aws dynamodb scan \
            --table-name "$table_name" \
            --region "$AWS_REGION" \
            --output json > "$output_file" 2>/dev/null || {
            echo -e "${RED}  Failed to dump $table_name (table may not exist or no permissions)${NC}"
            rm -f "$output_file"
            return 1
        }
    fi
    
    # Count items
    local item_count=$(jq -r '.Items | length' "$output_file" 2>/dev/null || echo "0")
    local file_size=$(du -h "$output_file" | cut -f1)
    
    if [ "$item_count" -gt 0 ]; then
        echo -e "${GREEN}  ✓ Dumped $item_count items ($file_size)${NC}"
    else
        echo -e "${YELLOW}  ⚠ Table is empty${NC}"
    fi
}

# List all tables
echo -e "${YELLOW}Listing all tables...${NC}"

if [ "$USE_LOCALSTACK" = true ]; then
    # Check if LocalStack is reachable
    if ! curl -s "$ENDPOINT_URL" > /dev/null 2>&1; then
        echo -e "${RED}Error: Cannot reach LocalStack at $ENDPOINT_URL${NC}"
        echo "Make sure LocalStack is running:"
        echo "  docker-compose up -d"
        echo "  # Or check if it's running:"
        echo "  docker ps | grep localstack"
        exit 1
    fi
    
    # Try to list tables with better error handling
    LIST_RESULT=$(aws dynamodb list-tables --endpoint-url "$ENDPOINT_URL" --region "$AWS_REGION" --output json 2>&1)
    LIST_EXIT_CODE=$?
    
    if [ $LIST_EXIT_CODE -ne 0 ]; then
        echo -e "${RED}Error listing tables:${NC}"
        echo "$LIST_RESULT"
        exit 1
    fi
    
    TABLES=$(echo "$LIST_RESULT" | jq -r '.TableNames[]?' 2>/dev/null || echo "")
    
    # Check if jq failed or if result is empty
    if [ -z "$TABLES" ]; then
        # Check if the JSON response indicates no tables
        TABLE_COUNT_JSON=$(echo "$LIST_RESULT" | jq -r '.TableNames | length' 2>/dev/null || echo "0")
        if [ "$TABLE_COUNT_JSON" = "0" ]; then
            echo -e "${YELLOW}No tables found in LocalStack${NC}"
            echo "Tables may not have been created yet."
            echo "Try running the application or tests to create tables."
            exit 0
        else
            echo -e "${RED}Error parsing table list${NC}"
            echo "Raw response:"
            echo "$LIST_RESULT"
            exit 1
        fi
    fi
else
    # AWS DynamoDB
    LIST_RESULT=$(aws dynamodb list-tables --output json 2>&1)
    LIST_EXIT_CODE=$?
    
    if [ $LIST_EXIT_CODE -ne 0 ]; then
        echo -e "${RED}Error listing tables:${NC}"
        echo "$LIST_RESULT"
        echo ""
        echo "Make sure AWS credentials are configured:"
        echo "  aws configure"
        echo "  # Or check current identity:"
        echo "  aws sts get-caller-identity"
        exit 1
    fi
    
    TABLES=$(echo "$LIST_RESULT" | jq -r '.TableNames[]?' 2>/dev/null || echo "")
    
    if [ -z "$TABLES" ]; then
        TABLE_COUNT_JSON=$(echo "$LIST_RESULT" | jq -r '.TableNames | length' 2>/dev/null || echo "0")
        if [ "$TABLE_COUNT_JSON" = "0" ]; then
            echo -e "${YELLOW}No tables found in AWS DynamoDB${NC}"
            exit 0
        else
            echo -e "${RED}Error parsing table list${NC}"
            echo "Raw response:"
            echo "$LIST_RESULT"
            exit 1
        fi
    fi
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

# Create a summary file
SUMMARY_FILE="$DUMP_DIR/SUMMARY.txt"
cat > "$SUMMARY_FILE" << EOF
DynamoDB Table Dump Summary
===========================
Timestamp: $(date)
Endpoint: $ENDPOINT_URL
Output Directory: $DUMP_DIR

Tables Found: $TABLE_COUNT
Successfully Dumped: $SUCCESS_COUNT
Failed: $FAILED_COUNT

Tables:
$(echo "$TABLES" | nl -w2 -s'. ')

Files:
$(ls -lh "$DUMP_DIR"/*.json 2>/dev/null | awk '{print $9, "(" $5 ")"}' || echo "No JSON files found")
EOF

echo -e "${GREEN}Summary saved to: $SUMMARY_FILE${NC}"

# Optionally create a combined JSON file
COMBINED_FILE="$DUMP_DIR/all_tables_combined.json"
echo -e "${YELLOW}Creating combined JSON file...${NC}"

{
    echo "{"
    echo "  \"dump_timestamp\": \"$(date -Iseconds)\","
    echo "  \"endpoint\": \"$ENDPOINT_URL\","
    echo "  \"tables\": {"
    
    FIRST=true
    for table in $TABLES; do
        if [ -f "$DUMP_DIR/${table}.json" ]; then
            if [ "$FIRST" = false ]; then
                echo ","
            fi
            FIRST=false
            echo -n "    \"$table\": "
            cat "$DUMP_DIR/${table}.json"
        fi
    done
    
    echo ""
    echo "  }"
    echo "}"
} > "$COMBINED_FILE"

echo -e "${GREEN}Combined file created: $COMBINED_FILE${NC}"
echo ""
echo -e "${GREEN}✓ Dump complete!${NC}"

