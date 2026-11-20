# Small, read-only CLI example. Run via scripts/bridge_cli.py run --script ...
Write-Output 'Hello from PhoneRescue'
Write-Output ('PowerShell ' + $PSVersionTable.PSVersion.ToString())
Write-Output ('Working directory: ' + (Get-Location).Path)
