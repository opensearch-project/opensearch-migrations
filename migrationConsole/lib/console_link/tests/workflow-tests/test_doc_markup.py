from console_link.workflow.tui.doc_markup import documentation_markup


def test_documentation_markup_allows_markdown_bold_and_escapes_rich_tags():
    assert documentation_markup("Choose **javascriptFile** for [red]external[/] files.") == (
        "Choose [bold]javascriptFile[/] for \\[red]external\\[/] files."
    )
