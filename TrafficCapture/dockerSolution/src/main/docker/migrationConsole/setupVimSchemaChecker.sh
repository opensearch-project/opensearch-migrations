#!/bin/bash
set -e

echo "=== Installing vim-plug ==="
curl -fLo ~/.vim/autoload/plug.vim --create-dirs \
    https://raw.githubusercontent.com/junegunn/vim-plug/master/plug.vim

echo "=== Configuring ~/.vimrc ==="
cat > ~/.vimrc << 'EOF'
call plug#begin()
Plug 'neoclide/coc.nvim', {'branch': 'release'}
call plug#end()

" Enable sign column (where error indicators appear)
set signcolumn=yes

" Show diagnostic messages faster
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

echo "=== Installing coc.nvim plugin ==="
vim +PlugInstall +qall

echo "=== Installing coc-yaml extension ==="
vim -c 'CocInstall -sync coc-yaml' +qall

echo "=== Vim setup complete ==="