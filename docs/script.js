const copyButton = document.getElementById('copy-command');

copyButton?.addEventListener('click', async () => {
  const command = `git clone https://github.com/io-org/tn5250_gateway.git
cd tn5250_gateway
mvn package
java -jar target/tn5250_gateway-1.0-SNAPSHOT.jar`;

  try {
    await navigator.clipboard.writeText(command);
    copyButton.textContent = 'Copied';
    setTimeout(() => { copyButton.textContent = 'Copy'; }, 1800);
  } catch {
    copyButton.textContent = 'Copy failed';
  }
});
