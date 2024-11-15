#!/usr/bin/env python3
import sys
import os
import yaml
from jinja2 import Environment, FileSystemLoader

def to_yaml_filter(value):
    """Custom filter to convert value to YAML format."""
    if value is None:
        return ''
    return yaml.dump(value, default_flow_style=False).rstrip()

def pop_value(dictionary, key, default=None):
    """Remove and return a value from a nested dictionary using dot notation."""
    keys = key.split('.')
    current = dictionary

    # Navigate to the parent of the target key
    for k in keys[:-1]:
        if k not in current:
            return default
        current = current[k]

    # Pop the final key
    return current.pop(keys[-1], default)


class YAMLTemplateConverter:
    def __init__(self, template_dir='.', template_file='template.yaml.j2'):
        """
        Initialize the converter with template directory and file.

        Args:
            template_dir (str): Directory containing the template files
            template_file (str): Name of the template file
        """
        self.template_dir = template_dir
        self.template_file = template_file

    def convert(self, inStream, outStream):
        # Read YAML from stdin
        values = yaml.safe_load(inStream)

        # Setup Jinja2 environment
        env = Environment(loader=FileSystemLoader(self.template_dir))
        env.filters['to_yaml'] = to_yaml_filter
        env.filters['pop_value'] = pop_value

        template = env.get_template(self.template_file)
        outStream.write(template.render(values=values))

def main():
    template_path = sys.argv[1] if len(sys.argv) > 1 else 'template.yaml.j2'
    template_dir = os.path.dirname(template_path) or '.'
    template_file = os.path.basename(template_path)

    try:
        YAMLTemplateConverter(template_dir, template_file).convert(sys.stdin, sys.stdout)
    except yaml.YAMLError as e:
        print(f"Error parsing YAML input: {e}", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)

if __name__ == '__main__':
    main()