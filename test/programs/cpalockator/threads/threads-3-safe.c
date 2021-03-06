//Test should check, how the tool handle single usage
int global;

typedef int pthread_mutex_t;
typedef unsigned long int pthread_t;
typedef int pthread_attr_t;
extern void pthread_mutex_lock(pthread_mutex_t *lock) ;
extern void pthread_mutex_unlock(pthread_mutex_t *lock) ;
extern int pthread_create(pthread_t *thread_id , pthread_attr_t const   *attr , void *(*func)(void * ) ,
                          void *arg ) ;

pthread_mutex_t mutex;

void* control_function(void* arg) {
	pthread_mutex_lock(&mutex);
	global = 1;
	pthread_mutex_unlock(&mutex);
}

int main() {
    pthread_t thread, thread2;
	pthread_create(&thread, 0, &control_function, 0);
	pthread_create(&thread2, 0, &control_function, 0);
}

