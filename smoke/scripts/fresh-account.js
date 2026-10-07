// A fresh identity from the email helper (email-helper/server.mjs), as output.account.
var response = http.get(EMAIL_HELPER + '/account')
if (!response.ok) throw new Error('Email helper /account returned ' + response.status)
output.account = json(response.body)
