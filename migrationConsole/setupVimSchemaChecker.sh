#!/bin/bash
set -e

echo "=== Installing vim-plug ==="
curl -fLo ~/.vim/autoload/plug.vim --create-dirs \
    https://raw.githubusercontent.com/junegunn/vim-plug/master/plug.vim

echo "=== Configuring ~/.vimrc ==="
cat > ~/.vimrc << 'EOF'
call plug#begin('~/.vim/plugged')
Plug 'neoclide/coc.nvim', {'branch': 'release'}
Plug 'tpope/vim-commentary'
call plug#end()

" Enable sign column (where error indicators appear)
set signcolumn=yes

" Show diagnostic messages faster (this is the key setting for responsiveness)
set updatetime=100

" Highlight errors
highlight CocErrorSign ctermfg=Red guifg=#ff0000
highlight CocWarningSign ctermfg=Yellow guifg=#ffff00

" Press K to show error details
function! ShowDocumentation()
  if CocAction('hasProvider', 'hover')
    call CocActionAsync('doHover')
  endif
endfunction
nnoremap <silent> K :call ShowDocumentation()<CR>

" Navigate between diagnostics with [g and ]g
nmap <silent> [g <Plug>(coc-diagnostic-prev)
nmap <silent> ]g <Plug>(coc-diagnostic-next)

" Show all diagnostics with <space>d
nnoremap <silent> <space>d :<C-u>CocList diagnostics<cr>

" Status line shows error count
set statusline^=%{coc#status()}

" Aggressively refresh diagnostics in insert mode
autocmd TextChangedI * call CocActionAsync('diagnosticRefresh')
autocmd TextChangedP * call CocActionAsync('diagnosticRefresh')

" Also refresh on cursor movement in insert mode
autocmd CursorMovedI * call CocActionAsync('diagnosticRefresh')
EOF

echo "=== Creating coc-settings.json ==="
mkdir -p ~/.vim
cat > ~/.vim/coc-settings.json << 'EOF'
{
  "diagnostic.refreshOnInsertMode": true,
  "diagnostic.checkCurrentLine": true,
  "diagnostic.virtualText": true,
  "diagnostic.messageDelay": 100,
  "diagnostic.refreshAfterSave": false,
  "yaml.validate": true,
  "yaml.hover": true,
  "yaml.completion": true,
  "yaml.format.enable": true,
  "yaml.trace.server": "verbose"
}
EOF

echo "=== Installing vim plugins ==="
vim +PlugInstall +qall

echo "=== Installing coc-yaml extension ==="
vim -c 'CocInstall -sync coc-yaml' +qall

echo ""
echo "=== Vim setup complete ==="
echo ""
echo "To use a schema with your YAML files, add this line at the top:"
echo "# yaml-language-server: \$schema=/path/to/your/schema.json"
echo ""
echo "Or configure schemas in ~/.vim/coc-settings.json like:"
echo '  "yaml.schemas": {'
echo '    "file:///path/to/schema.json": "*.config.yaml"'
echo '  }'