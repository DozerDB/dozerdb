#!/usr/bin/env python3
"""Build the DozerDB image from the assembled archive using a minimal context.

By default the image is loaded into the local Docker engine as dozerdb:local.
With --push the image is built for every requested platform and pushed under
each --tag instead (a multi-platform build cannot be loaded locally)."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent.parent

def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--platform', help='Buildx target platform(s), e.g. linux/amd64 or linux/amd64,linux/arm64')
    parser.add_argument('--tag', action='append', help='image tag; repeatable (default dozerdb:local)')
    parser.add_argument('--push', action='store_true', help='push the tags to their registry instead of loading locally')
    args = parser.parse_args(argv)
    if args.push and not args.tag:
        parser.error('--push needs at least one explicit --tag')
    args.tag = args.tag or ['dozerdb:local']
    return args

def build_command(args, name):
    command = ['docker', 'buildx', 'build', '--push' if args.push else '--load']
    for tag in args.tag:
        command += ['--tag', tag]
    command += ['--build-arg', f'NEO4J_TARBALL={name}']
    if args.platform:
        command += ['--platform', args.platform]
    return command

def main():
    args = parse_args()
    config = json.loads((ROOT / 'distribution/config.json').read_text())
    name = f'dozerdb-{config["distributionVersion"]}-unix.tar.gz'
    archive = ROOT / 'distribution/target' / name
    checksum = archive.with_name(name+'.sha256')
    expected = checksum.read_text().strip().split()
    if expected != [hashlib.sha256(archive.read_bytes()).hexdigest(), name]:
        raise SystemExit('Standalone archive checksum mismatch')
    context = ROOT / '.build/docker-context'
    if context.exists():
        shutil.rmtree(context)
    context.mkdir(parents=True)
    shutil.copy2(ROOT / 'docker/Dockerfile', context)
    shutil.copytree(ROOT / 'docker/local-package', context / 'local-package')
    shutil.copy2(archive, context)
    shutil.copy2(checksum, context)
    subprocess.run([*build_command(args, name), str(context)], check=True)

if __name__ == '__main__':
    main()
