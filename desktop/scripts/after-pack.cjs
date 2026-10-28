// Sets the icon and version info of Dopomine.exe without Wine (electron-builder's rcedit needs it
// when building on Linux or macOS). Runs after packing, before the installer is made.
const fs = require("node:fs");
const path = require("node:path");

exports.default = async function afterPack(context) {
  if (context.electronPlatformName != "win32") return;
  const ResEdit = await import("resedit");
  const { NtExecutable, NtExecutableResource, Resource, Data } = ResEdit.default ?? ResEdit;
  const exe = path.join(context.appOutDir, `${context.packager.appInfo.productFilename}.exe`);
  const ico = path.join(context.packager.projectDir, "build", "icon.ico");
  const executable = NtExecutable.from(fs.readFileSync(exe));
  const res = NtExecutableResource.from(executable);

  const iconFile = Data.IconFile.from(fs.readFileSync(ico));
  Resource.IconGroupEntry.replaceIconsForResource(res.entries, 1, 1033, iconFile.icons.map(i => i.data));

  const version = context.packager.appInfo.version;
  const [major, minor, patch] = version.split(".").map(Number);
  const info = Resource.VersionInfo.fromEntries(res.entries)[0] ?? Resource.VersionInfo.createEmpty();
  info.setFileVersion(major, minor, patch, 0, 1033);
  info.setProductVersion(major, minor, patch, 0, 1033);
  info.setStringValues({ lang: 1033, codepage: 1200 }, {
    FileDescription: "Dopomine",
    ProductName: "Dopomine",
    CompanyName: "dopamine",
    LegalCopyright: "Created by dopamine",
    OriginalFilename: "Dopomine.exe",
    InternalName: "Dopomine",
    FileVersion: version,
    ProductVersion: version,
  });
  info.outputToResourceEntries(res.entries);

  res.outputResource(executable);
  fs.writeFileSync(exe, Buffer.from(executable.generate()));
  console.log("  • set icon and version info  file=" + path.basename(exe));
};
