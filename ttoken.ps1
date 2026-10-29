import os
from dotenv import load_dotenv
from north import NorthClient

load_dotenv()

token = os.getenv("NORTH_AUTH_TOKEN")

print("Token found:", bool(token))
print("Token length:", len(token) if token else 0)
print("Token starts with:", token[:10] if token else "NONE")

co = NorthClient(
    auth_token="eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJjb2hlcmUtdG9vbGtpdCIsImlhdCI6MTc4ODIxNzk4NiwiZXhwIjoxNzg4ODIyNzg2LCJqdGkiOiI1ZGNlYTI2OC01ZGRlLTQ5NjAtYmUzYS1mM2VmN2Q3MmJkN2EiLCJzdWIiOiIwNDE2MWQyZC1hYTAyLTQ3NjAtYTAxYi1iYjgxZGJhNjMzZTEiLCJjb250ZXh0Ijp7ImlkIjoiMDQxNjFkMmQtYWEwMi00NzYwLWEwMWItYmI4MWRiYTYzM2UxIiwiZnVsbG5hbWUiOiJGYWlzYWwgTmt1dHUiLCJlbWFpbCI6ImZhaXNhbC5ua3V0dUBjYWxpYW4uY29tIiwiaGFzX2NvbXBsZXRlZF9vbmJvYXJkaW5nIjp0cnVlfSwic2NvcGUiOiIqIn0._dubfezmguhLz3AknxWP91yMz6pTBZYQP0K04fErSY0",
    base_url="https://north.canadacentral.cloudapp.azure.com/api"
)

try:
    response = co.chat(
        messages=[
            {
                "role": "user",
                "content": "Hello, please respond with a short test message."
            }
        ]
    )

    print("\nSUCCESS")
    print(response)

except Exception as e:
    print("\nFAILED")
    print(type(e).__name__)
    print(str(e))