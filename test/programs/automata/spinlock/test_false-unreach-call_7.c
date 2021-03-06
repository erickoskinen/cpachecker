typedef struct spinlock {
	union {
		void* rlock;
		struct {
			int __padding[128];
		};
	};
} spinlock_t;

typedef struct {
	int counter;
} atomic_t;


int some_func(spinlock_t *lock);

static inline void spin_lock(spinlock_t *lock);
static inline void spin_unlock(spinlock_t *lock);
static inline int spin_trylock(spinlock_t *lock) {
    return some_func(lock);
}
static inline int spin_is_locked(spinlock_t *lock);
extern int _atomic_dec_and_lock(atomic_t *atomic, spinlock_t *lock);


void ldv_linux_spinlock_check_final_state(void);

void main(void)
{
	spinlock_t *lock_1;
	spinlock_t *lock_2;
	atomic_t *atomic;
	int is_locked;

	is_locked = spin_trylock(lock_1);
	/* ignore is_locked, spin_is_locked may return true */
	if (spin_is_locked(lock_1)) {
		spin_unlock(lock_1);
	}

	ldv_linux_spinlock_check_final_state();
}

