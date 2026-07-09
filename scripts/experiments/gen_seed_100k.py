import random
from datetime import datetime, timedelta

random.seed(42)  # deterministic

out = open('scripts/experiments/zhishu-admin-seed-100k.sql', 'w', encoding='utf-8')
out.write("USE zhishu_exp_f07;\n")
out.write("DELETE FROM users WHERE username LIKE 'synthetic_user%';\n")

rows = []
for i in range(1, 100001):
    is_admin = random.random() < 0.10
    uname = f"synthetic_user_test_{i}" if i % 10 == 0 else f"synthetic_user_{i}"
    tc = 1 + int(random.random() * 3)
    tags = "default"
    if tc >= 2:
        tags += f",ORG_{1+int(random.random()*5)}"
    if tc >= 3:
        tags += f",ORG_{1+int(random.random()*5)}"
    role = "ADMIN" if is_admin else "USER"
    ca = (datetime(2026, 1, 1, 0, 0, 0) + timedelta(seconds=i)).strftime("%Y-%m-%d %H:%M:%S")
    pw = "$2a$10$placeholderhashplaceholderhashplaceholderhashplaceholderhashplacehold"
    rows.append(f"('{uname}','{pw}','{role}','{tags}','default','{ca}','{ca}')")
    if len(rows) == 1000:
        out.write("INSERT INTO users (username,password,role,org_tags,primary_org,created_at,updated_at) VALUES " + ",".join(rows) + ";\n")
        rows = []
if rows:
    out.write("INSERT INTO users (username,password,role,org_tags,primary_org,created_at,updated_at) VALUES " + ",".join(rows) + ";\n")

out.write("SELECT COUNT(*) total, SUM(role='USER') users, SUM(username LIKE '%test%') kw, SUM(FIND_IN_SET('ORG_1',org_tags)>0) org1, COUNT(DISTINCT created_at) dca FROM users;\n")
out.close()
print("generated 100k seed SQL")
