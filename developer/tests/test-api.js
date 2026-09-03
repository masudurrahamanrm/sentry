/**
 * Simple Node script to test the developer cloud backend
 */
const https = require('https');

const BASE_URL = 'https://sentry-devloper-version.onrender.com';

function checkUrl(path) {
  return new Promise((resolve, reject) => {
    https.get(`${BASE_URL}${path}`, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        console.log(`[${res.statusCode}] ${path}`);
        try {
          console.log(JSON.stringify(JSON.parse(data), null, 2));
        } catch {
          console.log(data.substring(0, 200));
        }
        resolve();
      });
    }).on('error', reject);
  });
}

async function runTests() {
  console.log(`Testing Developer Backend at: ${BASE_URL}\n`);
  await checkUrl('/');
  console.log('\n----------------------------------------\n');
  await checkUrl('/health');
}

runTests().catch(console.error);
