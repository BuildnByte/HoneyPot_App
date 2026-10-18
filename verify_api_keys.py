import json
import os
import sys
import urllib.request
import urllib.error

def load_local_properties(filepath="local.properties"):
    props = {}
    if not os.path.exists(filepath):
        print(f"[!] {filepath} not found in current directory.")
        return props
    
    with open(filepath, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if "=" in line:
                k, v = line.split("=", 1)
                # Unescape backslashes if present
                v = v.replace("\\:", ":").replace("\\\\", "\\").strip()
                props[k.strip()] = v
    return props

def check_safe_browsing(api_key):
    print("\n" + "="*50)
    print("Testing Google Safe Browsing API...")
    print("="*50)
    
    if not api_key:
        print("[!] SAFE_BROWSING_API_KEY is not set or empty.")
        return False
        
    masked_key = api_key[:6] + "..." + api_key[-4:] if len(api_key) > 10 else "***"
    print(f"Key: {masked_key}")

    url = f"https://safebrowsing.googleapis.com/v4/threatMatches:find?key={api_key}"
    
    # Test 1: Known malicious URL
    malicious_test_url = "http://malware.testing.google.test/testing/malware/"
    payload = {
        "client": {
            "clientId": "honeypot-verification-script",
            "clientVersion": "1.0"
        },
        "threatInfo": {
            "threatTypes": ["MALWARE", "SOCIAL_ENGINEERING", "UNWANTED_SOFTWARE"],
            "platformTypes": ["ANY_PLATFORM"],
            "threatEntryTypes": ["URL"],
            "threatEntries": [
                {"url": malicious_test_url}
            ]
        }
    }
    
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json"}
    )
    
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            code = resp.getcode()
            body = json.loads(resp.read().decode("utf-8"))
            print(f"[+] Status Code: {code}")
            
            matches = body.get("matches", [])
            if len(matches) > 0:
                print(f"[+] SUCCESS! API key is valid and working.")
                print(f"    Correctly detected test malware link: {matches[0].get('threatType')}")
                return True
            else:
                print("[+] Key is valid (HTTP 200), but no match returned for test URL.")
                print(f"    Raw response: {body}")
                return True
    except urllib.error.HTTPError as e:
        error_body = e.read().decode("utf-8", errors="replace")
        print(f"[-] FAILED! HTTP Error {e.code}: {e.reason}")
        try:
            err_json = json.loads(error_body)
            print(f"    Error details: {err_json.get('error', {}).get('message', error_body)}")
        except Exception:
            print(f"    Error body: {error_body}")
        return False
    except Exception as e:
        print(f"[-] Connection Error: {e}")
        return False

def check_gemini(api_key):
    print("\n" + "="*50)
    print("Testing Google Gemini API...")
    print("="*50)
    
    if not api_key:
        print("[!] GEMINI_API_KEY is not set or empty.")
        return False
        
    masked_key = api_key[:6] + "..." + api_key[-4:] if len(api_key) > 10 else "***"
    print(f"Key: {masked_key}")

    # Step 1: Query models list
    models_url = f"https://generativelanguage.googleapis.com/v1beta/models?key={api_key}"
    req_models = urllib.request.Request(models_url)
    
    try:
        with urllib.request.urlopen(req_models, timeout=10) as resp:
            code = resp.getcode()
            body = json.loads(resp.read().decode("utf-8"))
            models = body.get("models", [])
            print(f"[+] Status Code: {code}")
            print(f"[+] Key is authenticated! Found {len(models)} accessible models.")
    except urllib.error.HTTPError as e:
        error_body = e.read().decode("utf-8", errors="replace")
        print(f"[-] FAILED! HTTP Error {e.code}: {e.reason}")
        try:
            err_json = json.loads(error_body)
            print(f"    Error details: {err_json.get('error', {}).get('message', error_body)}")
        except Exception:
            print(f"    Error body: {error_body}")
        return False
    except Exception as e:
        print(f"[-] Connection Error: {e}")
        return False

    # Step 2: Test content generation with a common model
    test_models = ["gemini-1.5-flash", "gemini-2.0-flash", "gemini-2.5-flash"]
    generation_success = False
    
    for model_name in test_models:
        gen_url = f"https://generativelanguage.googleapis.com/v1beta/models/{model_name}:generateContent?key={api_key}"
        gen_payload = {
            "contents": [
                {
                    "role": "user",
                    "parts": [{"text": "Reply with 'API_OK' only."}]
                }
            ],
            "generationConfig": {
                "maxOutputTokens": 20
            }
        }
        
        data = json.dumps(gen_payload).encode("utf-8")
        req_gen = urllib.request.Request(
            gen_url,
            data=data,
            headers={"Content-Type": "application/json"}
        )
        
        try:
            with urllib.request.urlopen(req_gen, timeout=10) as resp:
                resp_body = json.loads(resp.read().decode("utf-8"))
                text = resp_body["candidates"][0]["content"]["parts"][0]["text"].strip()
                print(f"[+] SUCCESS! Generation test passed using '{model_name}': {text}")
                generation_success = True
                break
        except urllib.error.HTTPError:
            continue
        except Exception:
            continue
            
    if not generation_success:
        print("[!] Note: Key authenticated, but couldn't generate text with default models.")
        
    return True

def main():
    props = load_local_properties("local.properties")
    
    gemini_key = props.get("GEMINI_API_KEY", "")
    safe_browsing_key = props.get("SAFE_BROWSING_API_KEY", "")
    
    sb_ok = check_safe_browsing(safe_browsing_key)
    gemini_ok = check_gemini(gemini_key)
    
    print("\n" + "="*50)
    print("VERIFICATION SUMMARY")
    print("="*50)
    print(f"Safe Browsing API : {'WORKING [PASS]' if sb_ok else 'FAILED [X]'}")
    print(f"Gemini API        : {'WORKING [PASS]' if gemini_ok else 'FAILED [X]'}")
    print("="*50)

if __name__ == "__main__":
    main()
