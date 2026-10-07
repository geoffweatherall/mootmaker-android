// The code emailed to EMAIL, as output.code. The helper answers at once (202 while waiting), so
// this polls rather than holding one request open for longer than Maestro's HTTP client allows.
var deadline = Date.now() + 130000
var code = null
while (code === null && Date.now() < deadline) {
  var response = http.get(EMAIL_HELPER + '/code?email=' + encodeURIComponent(EMAIL))
  if (response.status === 200) {
    code = json(response.body).code
  } else if (response.status === 202) {
    var until = Date.now() + 2000
    while (Date.now() < until) {}
  } else {
    throw new Error('Email helper /code returned ' + response.status + ': ' + response.body)
  }
}
if (code === null) throw new Error('No verification code arrived for ' + EMAIL)
output.code = code
