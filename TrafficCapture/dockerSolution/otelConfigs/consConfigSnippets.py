import sys
import subprocess
import yaml


def collect_snippet_dependencies(key, original_dict,
                                 depth,
                                 ground_truth_dict, already_collected_set,
                                 found_at_depth_map):

    already_collected_set.add(key)
    if ground_truth_dict is None:
        return False
    found_match = False
    for parent, inner_map in ground_truth_dict.items():
        if parent == key or collect_snippet_dependencies(key, original_dict, depth + 1,
                                                         inner_map, already_collected_set,
                                                         found_at_depth_map):
            if parent not in already_collected_set:
                collect_snippet_dependencies(parent, original_dict, 0,
                                             original_dict, already_collected_set, found_at_depth_map)
            found_at_depth_map[parent] = depth
            found_match = True
    return found_match


def construct_command(selected_keys, deps):
    dependency_depth_dict = {}
    for key in selected_keys:
        found_key = collect_snippet_dependencies(key, deps, 0, deps, set(), dependency_depth_dict)
        assert found_key, f"key={key}"
    ordered_snippets = sorted(dependency_depth_dict, key=lambda k: dependency_depth_dict[k])

    files = ' '.join([f"configSnippets/{dep}.yaml" for dep in ordered_snippets])
    return f"yq eval-all '. as $item ireduce ({{}}; . *+ $item )' {files}"


def run_command(command):
    subprocess.run(command, shell=True, text=True)


def main(selected_keys):
    with open('dependencies.yml', 'r') as file:
        deps = yaml.safe_load(file)

    command = construct_command(selected_keys, deps)
    run_command(command)


if __name__ == "__main__":
    args = sys.argv[1:]  # Arguments from command line
    main(args)
