// Explicit opt-in Windows runtime check. Does not bind a port or write preferences.
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { createLocalPresetSyncService } from '../server/preset-sync/index.mjs';

assert.equal(process.platform,'win32');
const directory=fileURLToPath(new URL('../../.setup/pitin-preset-sync/',import.meta.url));
const file=directory+'preferences.json';
const digest=()=>createHash('sha256').update(readFileSync(file)).digest('hex');
const before=digest();let app;
try {
  app=createLocalPresetSyncService();
  assert.equal(app.httpServer.listening,false);assert.equal(app.httpsServer.listening,false);
  console.log('FACTORY_READY_NO_LISTENERS');
  const result=execFileSync('powershell.exe',['-NoProfile','-NonInteractive','-Command',`
    $ErrorActionPreference='Stop'
    $sid=[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $directory=[System.IO.DirectoryInfo]::new($env:PITIN_PRESET_PRIVATE_DIRECTORY)
    $items=@($directory)+@($directory.GetFiles())
    foreach($item in $items){
      $acl=$item.GetAccessControl()
      if($item -is [System.IO.DirectoryInfo] -and -not $acl.AreAccessRulesProtected){throw 'Directory ACL inheritance not protected'}
      $rules=$acl.GetAccessRules($true,$true,[System.Security.Principal.SecurityIdentifier])
      if($rules.Count -ne 1 -or $rules[0].IdentityReference.Value -ne $sid -or $rules[0].AccessControlType -ne 'Allow' -or $rules[0].FileSystemRights -ne 'FullControl'){throw 'ACL is not current-user-only full control'}
    }
    'CURRENT_USER_ONLY_ACL_VERIFIED'
  `],{env:{...process.env,PITIN_PRESET_PRIVATE_DIRECTORY:directory},windowsHide:true,encoding:'utf8'});
  console.log(result.trim());
} finally {if(app)await app.close();}
assert.equal(digest(),before);assert.equal(existsSync(file+'.lock'),false);
console.log('PREFERENCES_UNCHANGED_LOCK_RELEASED');
