"""The probe runner of the release check scripts: named probes of one delivery form, each printed as PASS or FAIL."""


class Probes:
    """Runs named probes of one form, printing PASS or FAIL for each and keeping the failures."""

    def __init__(self, form):
        self.form = form
        self.failures = []

    def run(self, name, check):
        """Run one probe: PASS with what check returned, or FAIL with the exception's message; the others still run."""
        try:
            print(f'PASS {self.form} {name}: {check()}', flush=True)
            return True
        except Exception as exc:  # a probe's failure of any kind is reported; the other probes still run
            message = str(exc).strip().replace('\n', '; ') or type(exc).__name__
            print(f'FAIL {self.form} {name}: {message}', flush=True)
            self.failures.append(f'{self.form} {name}')
            return False

    def skip(self, name, reason):
        print(f'SKIP {self.form} {name}: {reason}', flush=True)
