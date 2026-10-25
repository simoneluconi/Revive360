@ The program's entry point, as glibc's own crt1 (sysdeps/arm/start.S) but with
@ no init/fini: the helper is linked against the camera's glibc 2.13, whose
@ __libc_start_main our toolchain's newer crt1 does not match.
	.text
	.global _start
	.type _start, %function
_start:
	mov	fp, #0
	mov	lr, #0
	pop	{ a2 }		@ argc
	mov	a3, sp		@ argv
	push	{ a3 }		@ stack end
	push	{ a1 }		@ rtld_fini, from the dynamic loader
	mov	ip, #0
	push	{ ip }		@ fini: none
	ldr	a1, =main
	mov	a4, #0		@ init: none
	bl	__libc_start_main
	bl	abort
	.size _start, .-_start
	.section .note.GNU-stack,"",%progbits
